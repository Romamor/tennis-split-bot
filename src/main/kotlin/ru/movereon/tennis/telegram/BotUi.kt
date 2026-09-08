package ru.movereon.tennis.telegram

import kotlinx.serialization.json.*
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class BotUi(private val service: GroupService, private val accounting: SqliteAccountingStore,
    private val state: TelegramStore, private val clock: Clock = Clock.systemUTC()) {
    private fun button(text: String, action: BotAction) = listOf(text to action)
    private fun home() = button("В меню", BotAction("menu"))
    private fun apply(command: WorkflowCommand, back: String, entity: String? = null, participant: String? = null) =
        BotAction("apply", entity = entity, participant = participant, command = command, back = back)
    private fun confirm(command: WorkflowCommand, back: String, entity: String? = null) =
        BotAction("confirm", entity = entity, command = command, back = back)
    fun today(member: VerifiedGroupMember): String = LocalDate.now(clock.withZone(ZoneId.of(service.timeZone(member)))).toString()
    private fun pagination(action: BotAction, count: Int): List<List<Pair<String,BotAction>>> = listOfNotNull(
        if (action.page > 0) button("← Назад", action.copy(page = action.page - 1)) else null,
        if ((action.page + 1) * 8 < count) button("Дальше →", action.copy(page = action.page + 1)) else null)
    private fun <T> page(values: List<T>, index: Int) = values.drop(index.coerceAtLeast(0) * 8).take(8)

    fun render(member: VerifiedGroupMember, action: BotAction): Screen {
        val editor = action.editorId?.let { state.editor(member.userId,member.groupId,it) ?: throw NoSuchElementException("Editor closed") }
        val order = if(action.kind in setOf("participants","balances","add_players","selection_more","payments")) editor?.order ?: service.attendanceOrder(member) else emptyList()
        val ranks = order.withIndex().associate { it.value to it.index }
        val allProfiles = service.participants(member,true)
        val byId = allProfiles.associateBy { it.participant.id }
        val profiles = order.mapNotNull { byId[it] } + allProfiles.filterNot { it.participant.id in ranks }
        val names = participantLabels(profiles.map { it.participant })
        val own = profiles.firstOrNull { it.participant.telegramUserId == member.userId }?.participant
        val manager = service.canManage(member)
        fun name(id: String?) = names[id].orEmpty().ifEmpty { "участник" }
        fun amount(value: Long) = if(value > 0) "+$value ₽" else "$value ₽"
        fun draft() = editor?.view() ?: service.draft(member, requireNotNull(action.entity))
        fun edit(field: String,back: String,participant: String? = null,value: String? = null,guest: Boolean = false) =
            BotAction("edit",entity=action.entity,field=field,back=back,participant=participant,value=value,guest=guest)
        fun commit(post: Boolean) = BotAction("commit_editor",entity=action.entity,field=if(post) "post" else "save")
        fun playerLine(content: DraftContent,player: PlayerInput): String {
            val guest=content.players.firstOrNull { it.participantId==player.participantId && it.plusOne }
            val inactive=profiles.firstOrNull { it.participant.id==player.participantId }?.participant?.active==false
            return "${name(player.participantId)}${if(inactive) " (не ходит)" else ""}: ${hoursLabel(player.minutes)}" +
                (guest?.let { " · +1: ${hoursLabel(it.minutes)}" } ?: "")
        }
        fun summary(content: DraftContent): String {
            val main=content.players.filterNot { it.plusOne }
            val players=main.take(6).joinToString("\n") { playerLine(content,it) }
            val payments=content.payments.take(10).joinToString(", ") { "${name(it.participantId)} ${it.amount} ₽" }
            val playerTail=if(main.size>6) "\n… ещё ${main.size-6}. Полный состав — в «Подробности»." else ""
            val paymentTail=if(content.payments.size>10) "; ещё ${content.payments.size-10} — в «Кто оплатил»" else ""
            return "${historyDate(content.date)} · ${main.size} участников + ${content.players.count { it.plusOne }} гостей\n" +
                "${players.ifEmpty { "Игроки пока не указаны" }}$playerTail\nОплатили: ${payments.ifEmpty { "пока не указано" }}$paymentTail\nВсего: ${content.payments.sumOf { it.amount }} ₽"
        }
        val screen = when(action.kind) {
            "menu" -> Screen("🏓 ${state.group(member.groupId)?.title}\n\n${own?.let { "Привет, ${it.name}!" } ?: "Свою запись можно связать с Telegram в «Ещё → Участники»."}",
                listOf(button("Записать тренировку",BotAction("create_draft",field=today(member))),
                    button("Кто кому должен",BotAction("plan")),button("Записать перевод",BotAction("transfer_direction")),
                    listOf("Ещё" to BotAction("more"),"Сменить группу" to BotAction("groups"))))
            "more" -> Screen("🏓 ${state.group(member.groupId)?.title}",listOfNotNull(
                button("Тренировки",BotAction("drafts")),button("История",BotAction("history")),
                button("Участники",BotAction("participants")),button("Балансы",BotAction("balances")),
                if(manager && state.troubled(member.groupId).isNotEmpty()) button("Восстановить сообщение в группе",BotAction("recovery")) else null,home()))
            "balances" -> {
                val visible = profiles.filter { it.participant.active != action.inactiveOnly }
                val inactive = profiles.filterNot { it.participant.active }
                val owe = inactive.filter { it.balance < 0 }.fold(java.math.BigInteger.ZERO) { sum,p -> sum-p.balance.toBigInteger() }
                val owed = inactive.filter { it.balance > 0 }.fold(java.math.BigInteger.ZERO) { sum,p -> sum+p.balance.toBigInteger() }
                Screen("Баланс группы · ${if(action.inactiveOnly) "Не ходят" else "Ходят"}\n\n" +
                    page(visible,action.page).joinToString("\n") { "${name(it.participant.id)}: ${amount(it.balance)}" }.ifEmpty { "Пока никого." } +
                    (if(!action.inactiveOnly && (owe.signum()!=0 || owed.signum()!=0)) "\n\nУ неактивных: должны $owe ₽; им должны $owed ₽." else "") +
                    "\n\nПлюс — участнику должны. Минус — должен он. Общий расчёт включает всех.",
                    pagination(action,visible.size)+listOf(button(if(action.inactiveOnly) "Ходят" else "Не ходят · ${inactive.size}",action.copy(inactiveOnly=!action.inactiveOnly,page=0)),
                        button("Кому перевести?",BotAction("plan")),button("История",BotAction("history")),home()))
            }
            "plan" -> {
                val plan = service.settlementPlan(member)
                Screen(if(plan.isEmpty()) "Расчёты закрыты." else "Чтобы закрыть текущие расчёты:\n\n" + page(plan, action.page).joinToString("\n") {
                    "${name(it.from.value)} → ${name(it.to.value)}: ${it.amount} ₽"
                } + "\n\nЭто предложение по текущим балансам. После новых расходов суммы могут измениться.",
                    pagination(action, plan.size) + listOf(button("Записать перевод", BotAction("transfer_direction")),button("Балансы участников",BotAction("balances")), home()))
            }
            "participants" -> {
                val visible = profiles.filter { it.participant.active != action.inactiveOnly }
                Screen("Участники · ${if(action.inactiveOnly) "Не ходят" else "Ходят"}. Частые игроки сверху.",
                    page(visible, action.page).map { button(name(it.participant.id).take(45), BotAction("profile", entity = it.participant.id)) } +
                        pagination(action, visible.size) + listOf(button("Добавить по имени", BotAction("ask", field = "name", back = "profile")),
                            button(if(action.inactiveOnly) "Ходят" else "Не ходят · ${profiles.count { !it.participant.active }}", action.copy(inactiveOnly = !action.inactiveOnly, page = 0)), home()))
            }
            "profile" -> {
                val item = profiles.single { it.participant.id == action.entity }
                val p = item.participant
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                if(p.telegramUserId == null && own == null) rows += button("Это я", confirm(WorkflowCommand.LinkSelf(p.id, p.version), "profile", p.id))
                if(manager || p.telegramUserId == member.userId) rows += button("Изменить имя", BotAction("ask", entity = p.id, version = p.version, field = "rename", back = "profile"))
                if(manager) {
                    rows += button(if(p.active) "Больше не ходит" else "Снова ходит", confirm(WorkflowCommand.SetActive(p.id, p.version, !p.active), "profile", p.id))
                    if(p.telegramUserId != null) {
                        rows += button("Снять ошибочную привязку", confirm(WorkflowCommand.CorrectLink(p.id, p.version, null), "profile", p.id))
                        rows += button("Назначить организатором", confirm(WorkflowCommand.AppointOrganizer(p.telegramUserId), "profile", p.id))
                    }
                }
                rows += button("К участникам", BotAction("participants"))
                Screen("${p.name}\nБаланс: ${amount(item.balance)}\n${if(p.active) "Ходит на тренировки" else "Больше не ходит; баланс сохраняется"}\n" +
                    (p.telegramUserId?.let { "Telegram: ${state.name(it)}" } ?: "Telegram пока не привязан"), rows)
            }
            "drafts" -> {
                val drafts = service.drafts(member, action.showAll)
                val local = state.editors(member.userId,member.groupId).filter { it.dirty }
                Screen("Тренировки${if(!action.showAll) " · незавершённые записи" else " · все записи"}\n${if(drafts.isEmpty()) "Пока пусто." else "Можно дополнить существующую запись."}",
                    local.take(5).map { button("Продолжить ввод · ${historyDate(it.content.date)}",BotAction("draft",entity=it.draftId,editorId=it.id)) } +
                    page(drafts, action.page).map { button("${it.content.date} · ${status(it.status)}", BotAction("draft", entity = it.id)) } +
                        pagination(action, drafts.size) + listOf(
                            button(if(action.showAll) "Только незавершённые" else "Показать все", action.copy(showAll = !action.showAll, page = 0)), home()))
            }
            "draft" -> {
                val d = draft()
                val shared = if(editor != null && editor.baseline.version>0) service.draft(member,d.id) else null
                val conflict = editor != null && shared != null && shared.version!=editor.baseline.version
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                if(d.status!=DraftStatus.CANCELLED) {
                    if(!conflict) {
                        if((d.status in setOf(DraftStatus.DRAFT,DraftStatus.EDITING) || editor?.dirty==true) && (d.financialVersion>0 || manager || member.userId==d.createdBy)) {
                            if(d.content.players.isNotEmpty() && d.content.payments.isNotEmpty())
                                rows += button(if(d.financialVersion==0L) "Записать тренировку" else "Сохранить изменения",commit(true))
                            else rows += button(if(d.content.players.isEmpty()) "Выбрать игроков" else "Добавить оплату",
                                BotAction(if(d.content.players.isEmpty()) "add_players" else "payments",entity=d.id))
                        }
                    } else rows += button("Загрузить общую версию",BotAction("reload_editor_confirm",entity=d.id))
                    rows += listOf("Игроки" to BotAction("players",entity=d.id),"Кто оплатил" to BotAction("payments",entity=d.id))
                }
                rows += listOf("Подробности" to BotAction("training_details",entity=d.id),"Ещё" to BotAction("draft_more",entity=d.id))
                rows += home()
                val previous = d.publishedContent
                val delta = if(previous != null && previous!=d.content) "\n\nПосле применения: расходы ${previous.payments.sumOf { it.amount }} → ${d.content.payments.sumOf { it.amount }} ₽; " +
                    "людей ${previous.players.size} → ${d.content.players.size}. Уже записанные возвраты сохраняются." else ""
                Screen("🏓 ${state.group(member.groupId)?.title}\n${if(editor?.dirty==true) { if(d.financialVersion>0) "Изменения ещё не сохранены" else "Тренировка ещё не записана" } else status(d.status)}\n${summary(d.content)}$delta" +
                    (if(conflict) "\n\nОбщую запись уже изменили. Твой ввод сохранён отдельно; загрузи общую версию перед сохранением." else
                        if(editor?.dirty==true) "\n\nБаланс изменится после записи тренировки." else ""),rows)
            }
            "draft_more" -> {
                val d = draft()
                val shared = if(editor != null && editor.baseline.version>0) service.draft(member,d.id) else null
                val conflict = editor != null && shared != null && shared.version!=editor.baseline.version
                Screen("Дополнительные действия тренировки",listOfNotNull(
                    if(conflict) button("Загрузить общую версию",BotAction("reload_editor_confirm",entity=d.id)) else null,
                    if(editor?.dirty==true && !conflict && d.status!=DraftStatus.CANCELLED) button("Сохранить на потом",commit(false)) else null,
                    if(d.status!=DraftStatus.CANCELLED) button("Дата",BotAction("ask",entity=d.id,field="draft_date",back="draft")) else null,
                    if(d.content.players.isNotEmpty() && d.content.payments.isNotEmpty()) button("Посмотреть расчёт",BotAction("preview",entity=d.id)) else null,
                    button(if(editor?.dirty==true) "Закрыть без сохранения" else "Закрыть",BotAction("close_editor",entity=d.id)),
                    if(d.version>0 && (manager || member.userId==d.createdBy) && d.status!=DraftStatus.CANCELLED)
                        button("Отменить тренировку",confirm(WorkflowCommand.CancelDraft(d.id,d.version),"draft",d.id)) else null,
                    if((editor?.baseline?.status ?: d.status)==DraftStatus.EDITING && d.version>0 && (manager || member.userId==d.createdBy))
                        button("Отбросить общие правки",confirm(WorkflowCommand.DiscardChanges(d.id,d.version),"draft",d.id)) else null,
                    if(d.version>0) button("Изменения записи",BotAction("changes",entity=d.id,field="draft")) else null,
                    button("К истории",BotAction("history")),button("К тренировке",BotAction("draft",entity=d.id))))
            }
            "training_details" -> {
                val d=draft()
                val main=d.content.players.filterNot { it.plusOne }
                val lines=page(main,action.page).joinToString("\n") { playerLine(d.content,it) }
                Screen("Полный состав · ${main.size} участников + ${d.content.players.count { it.plusOne }} гостей\n$lines",pagination(action,main.size)+listOf(button("К тренировке",BotAction("draft",entity=d.id))))
            }
            "reload_editor_confirm" -> Screen("Загрузить общую версию? Твой несохранённый ввод будет заменён.",
                listOf(button("Загрузить общую версию",action.copy(kind="reload_editor")),button("К моему вводу",BotAction("draft",entity=action.entity))))
            "players" -> {
                val d = draft(); val players = d.content.players.filterNot { it.plusOne }
                Screen("Выбери игрока, чтобы изменить время или добавить +1.", page(players, action.page).map {
                    val guest = d.content.players.firstOrNull { guest -> guest.participantId == it.participantId && guest.plusOne }
                    val label = "${name(it.participantId).take(30)} · ${hoursLabel(it.minutes)}" +
                        (guest?.let { " · +1: ${hoursLabel(it.minutes)}" } ?: "")
                    button(label, BotAction("player", entity = d.id, participant = it.participantId))
                } + pagination(action, players.size) + listOf(button("Выбрать игроков", BotAction("add_players", entity = d.id)), button("Время всем",BotAction("time_choices",entity=d.id,field="all_minutes",back="players")), button("Дальше: оплаты", BotAction("payments", entity = d.id)), button("К тренировке", BotAction("draft", entity = d.id))))
            }
            "add_players" -> {
                val d = draft()
                val selected = d.content.players.filterNot { it.plusOne }.map { it.participantId }.toSet()
                val visible = profiles.filter { it.participant.active != action.inactiveOnly }
                val pageSize = 12
                val currentPage = action.page.coerceIn(0,((visible.size-1).coerceAtLeast(0))/pageSize)
                val choices = visible.drop(currentPage*pageSize).take(pageSize).map {
                    val id = it.participant.id
                    val checked = id in selected
                    val guest = d.content.players.any { it.participantId==id && it.plusOne }
                    val label = "${if(checked) "✅" else "⬜"} ${name(id).take(28)}${if(guest) " +1" else ""}"
                    label to edit("toggle_player","add_players",id).copy(page=currentPage,inactiveOnly=action.inactiveOnly)
                }
                val navigation = buildList {
                    if(currentPage > 0) add("← Назад" to action.copy(page=currentPage-1))
                    if((currentPage+1)*pageSize < visible.size) add("Дальше →" to action.copy(page=currentPage+1))
                }
                val rows = choices.chunked(2) + listOfNotNull(navigation.takeIf { it.isNotEmpty() }) +
                    listOfNotNull(
                        if(visible.isEmpty()) button("Добавить игрока",BotAction("ask",field="name",back="add_players",entity=d.id)) else null,
                        if(selected.isNotEmpty()) button("Дальше: оплаты · ${selected.size}",BotAction("payments",entity=d.id)) else null,
                        if(selected.isNotEmpty()) button("Время и +1",BotAction("players",entity=d.id)) else null,
                        listOf("Ещё" to BotAction("selection_more",entity=d.id,page=currentPage,inactiveOnly=action.inactiveOnly),"В меню" to BotAction("menu")))
                Screen("🏓 ${state.group(member.groupId)?.title}\nКто играл?${if(action.inactiveOnly) " · Не ходят" else ""}\n" +
                    "Выбрано: ${selected.size}" + (if(d.content.players.any { it.plusOne }) " · гостей +1: ${d.content.players.count { it.plusOne }}" else "") +
                    " · по умолчанию 1 ч" + (if(visible.size>pageSize) "\nСтраница ${currentPage+1} из ${(visible.size-1)/pageSize+1}" else ""),rows)
            }
            "selection_more" -> {
                val d = draft()
                val selected = d.content.players.filterNot { it.plusOne }.map { it.participantId }.toSet()
                val visible = profiles.filter { it.participant.active != action.inactiveOnly }
                val previous = service.drafts(member,true).filter { it.id!=d.id && it.status!=DraftStatus.CANCELLED && it.publishedContent!=null }
                    .maxWithOrNull(compareBy<TrainingDraft> { it.publishedContent!!.date }.thenBy { it.id })
                Screen("Выбор игроков · ${state.group(member.groupId)?.title}",listOfNotNull(
                    if(visible.any { it.participant.id !in selected }) button("Выбрать всех",edit("all_players","add_players").copy(page=action.page,inactiveOnly=action.inactiveOnly)) else null,
                    if(selected.isNotEmpty()) button("Снять весь выбор",edit("clear_players","add_players").copy(page=action.page,inactiveOnly=action.inactiveOnly)) else null,
                    previous?.let { button("Повторить состав за ${historyDate(it.publishedContent!!.date)}",edit("previous_players","add_players",value=it.id).copy(version=it.version)) },
                    button(if(action.inactiveOnly) "Ходят" else "Не ходят · ${profiles.count { !it.participant.active }}",BotAction("add_players",entity=d.id,inactiveOnly=!action.inactiveOnly)),
                    button("Добавить человека по имени",BotAction("ask",field="name",back="add_players",entity=d.id)),
                    button("Сохранить на потом",commit(false)),
                    button("К выбору игроков",BotAction("add_players",entity=d.id,page=action.page,inactiveOnly=action.inactiveOnly))))
            }
            "player" -> {
                val d = draft(); val id = requireNotNull(action.participant)
                val player = d.content.players.single { it.participantId == id && !it.plusOne }
                val guest = d.content.players.firstOrNull { it.participantId == id && it.plusOne }
                val rows = mutableListOf(button("Изменить время", BotAction("time_choices", entity = d.id, participant = id, field = "minutes", back = "player")),
                    button(if(guest == null) "Добавить +1" else "Убрать +1", edit("guest","player",id)))
                if(guest != null) rows += button("Изменить время +1", BotAction("time_choices", entity = d.id, participant = id, field = "minutes", guest = true, back = "player"))
                rows += button("Убрать игрока", edit("remove_player","players",id))
                rows += button("К игрокам", BotAction("players", entity = d.id))
                Screen("${name(id)}: ${hoursLabel(player.minutes)}${guest?.let { " · +1: ${hoursLabel(it.minutes)}. Его доля относится на ${name(id)}." } ?: ""}", rows)
            }
            "payments" -> {
                val d = draft()
                val selected = d.content.players.map { it.participantId }.toSet()
                val paidIds = d.content.payments.map { it.participantId }.toSet()
                val visible = profiles.filter { action.showAll || it.participant.id in selected || it.participant.id in paidIds }
                val shared = if(editor != null && editor.baseline.version>0) service.draft(member,d.id) else null
                val conflict = editor != null && shared != null && shared.version!=editor.baseline.version
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                if(d.status!=DraftStatus.CANCELLED) {
                    if(!conflict && d.content.players.isNotEmpty() && d.content.payments.isNotEmpty() &&
                        (d.status in setOf(DraftStatus.DRAFT,DraftStatus.EDITING) || editor?.dirty==true) && (d.financialVersion>0 || manager || member.userId==d.createdBy))
                        rows += button(if(d.financialVersion==0L) "Записать тренировку" else "Сохранить изменения",commit(true))
                    if(conflict) rows += button("Загрузить общую версию",BotAction("reload_editor_confirm",entity=d.id))
                    if(own!=null && page(visible,action.page).none { it.participant.id==own.id })
                        rows += button("Я оплатил",BotAction("ask",entity=d.id,participant=own.id,field="payment",back="payments",page=action.page,showAll=action.showAll))
                    rows += page(visible,action.page).map {
                        val paid = d.content.payments.firstOrNull { p -> p.participantId == it.participant.id }?.amount
                        button("${name(it.participant.id).take(40)}${paid?.let { " · $it ₽" } ?: ""}",
                            BotAction("ask",entity=d.id,participant=it.participant.id,field="payment",back="payments",page=action.page,showAll=action.showAll))
                    }
                    rows += pagination(action,visible.size)
                    if(profiles.any { it.participant.id !in selected && it.participant.id !in paidIds })
                        rows += button(if(action.showAll) "Только игроки и оплатившие" else "Оплатил другой человек",action.copy(showAll=!action.showAll,page=0))
                }
                rows += listOf("Игроки" to BotAction("players",entity=d.id),"Ещё" to BotAction("draft_more",entity=d.id))
                rows += button("К тренировке",BotAction("draft",entity=d.id))
                Screen("🏓 ${state.group(member.groupId)?.title}\n${summary(d.content)}\n\nКто оплатил стол? Нажми на имя и укажи сумму." +
                    (if(conflict) "\nОбщую запись уже изменили. Твой ввод сохранён отдельно." else ""),rows)
            }
            "preview" -> {
                val d = draft(); val allocation = calculateTraining(d.content.training())
                val previous = applyEntries(emptyMap(),accounting.history(member.groupId).filter {
                    it.command.entityId == d.id && it.event.kind.startsWith("training_")
                }.flatMap { it.event.entries })
                val current = accounting.balances(member.groupId)
                val updated = applyEntries(current,previous.map { BalanceEntry(it.key,-it.value) } + allocation.entries)
                val affected = (previous.keys + allocation.entries.map { it.participant }).distinct()
                val text = "Всего ${allocation.total} ₽\n\n" + page(allocation.participantShares.entries.toList(),action.page).joinToString("\n") { "${name(it.key.value)}: доля ${it.value} ₽" } +
                    "\n\nБалансы после сохранения:\n" + page(affected,action.page).joinToString("\n") { "${name(it.value)}: ${amount(current.getOrDefault(it,0))} → ${amount(updated.getOrDefault(it,0))}" }
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                if(d.status in setOf(DraftStatus.DRAFT,DraftStatus.EDITING) && (d.financialVersion>0 || manager || member.userId == d.createdBy))
                    rows += button(if(d.financialVersion == 0L) "Записать тренировку" else "Сохранить изменения", commit(true))
                rows += button("Исправить", BotAction("draft", entity = d.id))
                Screen(text, rows + pagination(action,maxOf(affected.size,allocation.participantShares.size)))
            }
            "time_choices" -> {
                val content=editor?.content ?: requireNotNull(action.draft)
                checkAccounting(content.players.isNotEmpty(),ErrorCode.INVALID_INPUT,"Pick players first")
                val page = action.page.coerceAtLeast(0)
                val choices = (1L..6L).map { index ->
                    val minutes = (page.toLong() * 6 + index) * 30
                    hoursLabel(minutes) to edit(requireNotNull(action.field),action.back ?: "draft",action.participant,minutes.toString(),action.guest)
                }
                val navigation = buildList {
                    if(page > 0) add("← Меньше" to action.copy(page=page-1))
                    if(page < Int.MAX_VALUE) add("Больше →" to action.copy(page=page+1))
                }
                Screen("Сколько времени играли? Выбери с шагом полчаса.",choices.chunked(3) + listOf(navigation,
                    button("Назад",BotAction(action.back ?: "draft",entity=action.entity,participant=action.participant))))
            }
            "transfer_direction" -> Screen("Какой перевод записать?",listOf(button("Я перевёл",BotAction("new_transfer")),button("Мне перевели",BotAction("new_transfer",field="incoming")),home()))
            "form" -> {
                val form = requireNotNull(action.form)
                val rows = mutableListOf(listOf("Отправитель" to action.copy(kind = "choose_from"), "Получатель" to action.copy(kind = "choose_to")),
                    listOf("Сумма" to BotAction("ask", field = "transfer_amount", form = form, back = "form"), "Дата" to BotAction("ask", field = "transfer_date", form = form, back = "form")),
                    button("Комментарий", BotAction("ask", field = "transfer_note", form = form, back = "form")))
                if(manager) rows += button("От чьего имени записать", action.copy(kind = "choose_represented"))
                rows += button("Проверить перевод", action.copy(kind = "transfer_preview"))
                rows += home()
                Screen("Перевод\n${form.from?.let(::name) ?: "Отправитель не выбран"} → ${form.to?.let(::name) ?: "Получатель не выбран"}\n" +
                    "Сумма: ${form.amount?.let { "$it ₽" } ?: "не указана"}\nДата: ${form.date}\n${form.note.orEmpty()}" +
                    (form.represented?.let { "\nОт имени: ${name(it)}" } ?: ""), rows)
            }
            "choose_from", "choose_to", "choose_represented" -> {
                val form = requireNotNull(action.form)
                val choices = profiles.filter { action.kind != "choose_represented" || it.participant.id in listOf(form.from, form.to) }
                Screen("Выбери участника:", page(choices, action.page).map {
                    val updated = when(action.kind) {
                        "choose_from" -> form.copy(from = it.participant.id)
                        "choose_to" -> form.copy(to = it.participant.id)
                        else -> form.copy(represented = it.participant.id)
                    }
                    button(name(it.participant.id).take(45), BotAction("form", form = updated))
                } + pagination(action, choices.size) + listOf(button("К переводу", BotAction("form", form = form))))
            }
            "transfer_preview" -> {
                val f = requireNotNull(action.form)
                checkAccounting(f.from != null && f.to != null && f.amount != null && f.amount > 0 && f.from != f.to, ErrorCode.INVALID_INPUT, "Incomplete transfer")
                Screen("Записать фактический перевод?\n${name(f.from)} → ${name(f.to)}: ${f.amount} ₽\n${f.date}\n${f.note.orEmpty()}" +
                    (f.represented?.let { "\nТы запишешь его от имени: ${name(it)}" } ?: "") + "\n\nАванс и частичный возврат тоже можно записать.",
                    listOf(button("Перевод состоялся — записать", apply(WorkflowCommand.RecordTransfer(f.id,f.from!!,f.to!!,f.amount!!,f.date,f.note,f.represented), "transfer", f.id)),
                        button("Исправить", BotAction("form", form = f))))
            }
            "transfer" -> {
                val id = requireNotNull(action.entity)
                val transfer = requireNotNull(accounting.transfer(member.groupId,id))
                val source = accounting.history(member.groupId).first { it.command is Command.RecordTransfer && it.command.entityId == id }
                val stateLabel = when(transfer.status) { TransferStatus.ACTIVE -> "Учтён"; TransferStatus.UNDER_REVIEW -> "Уточняем · пока не учитывается"; else -> "Запись отменена" }
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                val eligible = own?.id in listOf(transfer.from.value,transfer.to.value) || action.represented != null
                if(transfer.status == TransferStatus.ACTIVE && eligible) rows += button("Уточнить перевод", confirm(WorkflowCommand.ChangeTransfer(id,transfer.version,TransferIntent.REVIEW, action.represented), "transfer", id))
                if(transfer.status == TransferStatus.UNDER_REVIEW && transfer.reviewInitiator == "telegram:${member.userId}") {
                    rows += button("Всё верно", apply(WorkflowCommand.ChangeTransfer(id,transfer.version,TransferIntent.CONFIRM,action.represented), "transfer",id))
                    rows += button("Записано по ошибке", confirm(WorkflowCommand.ChangeTransfer(id,transfer.version,TransferIntent.CANCEL,action.represented), "transfer",id))
                }
                if(manager) rows += listOf("От имени отправителя" to action.copy(represented = transfer.from.value), "От имени получателя" to action.copy(represented = transfer.to.value))
                rows += button("Изменения записи", BotAction("changes",entity=id,field="transfer"))
                rows += button("К истории", BotAction("history"))
                rows += button("Обновить", action); rows += home()
                val latest = accounting.history(member.groupId).last { it.command.entityId == id && it.event.kind.startsWith("transfer_") }
                val undelivered = listOfNotNull(profiles.find { it.participant.id == transfer.from.value }?.participant?.telegramUserId,
                    profiles.find { it.participant.id == transfer.to.value }?.participant?.telegramUserId).any {
                    state.delivery("notice:${member.groupId}:${latest.receipt.sequence}:$it")?.status in setOf("BLOCKED","FAILED","UNKNOWN")
                }
                Screen("${name(transfer.from.value)} → ${name(transfer.to.value)}: ${transfer.amount} ₽\n$stateLabel\n${source.details.occurredOn ?: ""}\n${source.details.note.orEmpty().take(300)}" +
                    (action.represented?.let { "\nДействуешь от имени: ${name(it)}" } ?: "") + if(undelivered) "\nЛичное уведомление второй стороне не доставлено или требует проверки." else "", rows)
            }
            "confirm" -> {
                val command = requireNotNull(action.command)
                val text = when(command) {
                    is WorkflowCommand.LinkSelf -> "Связать твой Telegram с записью «${name(command.id)}»? Баланс и история сохранятся."
                    is WorkflowCommand.SetActive -> if(command.active) "Вернуть участника в обычный список?" else "Отметить, что участник больше не ходит? Его долг или остаток сохранятся."
                    is WorkflowCommand.CorrectLink -> "Снять привязку Telegram? Финансовая запись останется, и нужный человек сможет выбрать «Это я»."
                    is WorkflowCommand.AppointOrganizer -> "Назначить этого участника организатором? Он сможет исправлять привязки и учитывать чужие тренировки."
                    is WorkflowCommand.CancelDraft -> "Отменить тренировку? Её расходы перестанут учитываться. Реальные переводы останутся в истории и балансах."
                    is WorkflowCommand.DiscardChanges -> "Отбросить общие рабочие правки и вернуть учтённый вариант?"
                    is WorkflowCommand.ChangeTransfer -> if(command.action == TransferIntent.REVIEW) "Пока убрать этот перевод из расчётов? После проверки можно будет нажать «Всё верно»." else "Отменить ошибочную запись? Она останется в истории."
                    else -> "Подтвердить действие?"
                }
                val label = if(command is WorkflowCommand.ChangeTransfer && command.action == TransferIntent.REVIEW) "Пока не учитывать" else "Подтвердить"
                Screen(text, listOf(button(label, action.copy(kind = "apply")), button("Оставить как есть", BotAction(action.back ?: "menu", entity = action.entity))))
            }
            "history" -> {
                val entries = service.audit(member).filter { historyFamily(it.kind) != null }
                    .groupBy { historyFamily(it.kind) to it.entityId }.values.sortedByDescending { it.last().id }
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                val descriptions = page(entries,action.page).mapIndexed { index, changes ->
                    val last = changes.last()
                    val number = action.page.coerceAtLeast(0).toLong() * 8 + index + 1
                    val title: String
                    val description: String
                    val target = requireNotNull(historyFamily(last.kind))
                    if(target == "draft") {
                        val d = Json.decodeFromString<TrainingDraft>(requireNotNull(last.afterJson))
                        // An unfinished edit must not look like an already applied expense.
                        val content = d.publishedContent ?: d.content
                        title = "Тренировка · ${historyDate(content.date)}"
                        description = "${content.payments.sumOf { it.amount }} ₽ · ${status(d.status)}"
                    } else {
                        val original = Json.parseToJsonElement(requireNotNull(changes.first().afterJson)).jsonObject
                        val current = Json.parseToJsonElement(requireNotNull(last.afterJson)).jsonObject
                        title = "Перевод" + (original["date"]?.jsonPrimitive?.content?.let { " · ${historyDate(it)}" } ?: "")
                        val transferStatus = current["status"]?.jsonPrimitive?.content ?: "ACTIVE"
                        description = "${name(original["from"]!!.jsonPrimitive.content)} → ${name(original["to"]!!.jsonPrimitive.content)}: ${original["amount"]!!.jsonPrimitive.content} ₽\n" +
                            when(transferStatus) { "ACTIVE" -> "Учтён"; "UNDER_REVIEW" -> "Уточняем · пока не учитывается"; else -> "Запись отменена" }
                    }
                    rows += button("$number. $title",BotAction(target,entity=last.entityId))
                    "$number. $title\n$description"
                }
                Screen("История\n\n" + descriptions.joinToString("\n\n").ifEmpty { "Тренировок и переводов пока нет." } +
                    "\n\nОдна запись — одна тренировка или перевод. Последние изменения сверху.",
                    rows + pagination(action,entries.size) + listOf(button("Все изменения",BotAction("changes")),home()))
            }
            "changes" -> {
                val audit = service.audit(member).filter {
                    action.entity == null || it.entityId == action.entity && historyFamily(it.kind) == action.field
                }.reversed()
                val zone = ZoneId.of(service.timeZone(member))
                val rows = page(audit,action.page).map {
                    val date = DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.parse(it.recordedAt).atZone(zone))
                    button("$date · ${auditLabel(it.kind)}",BotAction("audit",entity=it.id.toString(),field=action.field,
                        participant=action.entity,page=action.page,back="changes"))
                }
                Screen(if(action.entity == null) "Все изменения\nПолный журнал, включая участников и настройки." else "Изменения записи\nВыбери правку, чтобы увидеть автора и подробности.",
                    rows + pagination(action,audit.size) + listOfNotNull(
                        action.entity?.let { button("К записи",BotAction(requireNotNull(action.field),entity=it)) },
                        button("К истории",BotAction("history"))))
            }
            "audit" -> {
                val event = service.audit(member).single { it.id.toString() == action.entity }
                fun readable(text: String?): String {
                    if(text == null) return "Не было"
                    val value = Json.parseToJsonElement(text).jsonObject
                    val content = value["content"]?.jsonObject
                    if(content != null) return (status(DraftStatus.valueOf(value["status"]!!.jsonPrimitive.content)) + "\n" +
                        summary(Json.decodeFromJsonElement<DraftContent>(content))).take(1400)
                    if(value["name"] != null) return "Имя: ${value["name"]!!.jsonPrimitive.content}\nTelegram: ${value["telegramUserId"]?.jsonPrimitive?.longOrNull?.let(state::name) ?: "не привязан"}\nХодит: ${if(value["active"]?.jsonPrimitive?.booleanOrNull == true) "да" else "нет"}"
                    if(value["amount"] != null) return "${name(value["from"]?.jsonPrimitive?.content)} → ${name(value["to"]?.jsonPrimitive?.content)}: ${value["amount"]?.jsonPrimitive?.content} ₽\n" +
                        when(value["status"]?.jsonPrimitive?.content ?: "ACTIVE") {
                            "ACTIVE" -> "Учтён"; "UNDER_REVIEW" -> "Уточняем · пока не учитывается"; else -> "Запись отменена"
                        }
                    return auditLabel(event.kind)
                }
                Screen("${auditLabel(event.kind)}\nАвтор: ${state.name(event.actorUserId)}\n${event.recordedAt.take(19)} UTC\n\nДо:\n${readable(event.beforeJson)}\n\nПосле:\n${readable(event.afterJson)}",
                    listOf(button(if(action.back == "changes") "К изменениям" else "К истории",
                        if(action.back == "changes") BotAction("changes",entity=action.participant,field=action.field,page=action.page)
                        else BotAction("history"))))
            }
            "recovery" -> {
                checkAccounting(manager,ErrorCode.FORBIDDEN,"Organizer required")
                val issues = state.troubled(member.groupId)
                Screen("Карточки, которым нужна проверка. Если карточка уже есть в группе, ответь на неё командой /restore. Если её нет — выбери запись ниже.",
                    issues.take(8).map { button(if(it.key.startsWith("menu:")) "Общее меню" else "Карточка тренировки", BotAction("recover_confirm",entity=it.key)) } + listOf(home()))
            }
            "recover_confirm" -> Screen("Проверь общий чат. Отправлять новую карточку стоит, только если прежней там нет.",
                listOf(button("В чате нет карточки — отправить", action.copy(kind="recover")),button("Назад",BotAction("recovery"))))
            else -> Screen("Открой нужный раздел из меню.",listOf(home()))
        }
        if(editor == null) return screen
        val formKinds = setOf("draft","players","add_players","selection_more","player","payments","preview","time_choices","draft_more","training_details",
            "edit","commit_editor","close_editor","reload_editor","reload_editor_confirm")
        return screen.copy(buttons=screen.buttons.map { row -> row.map { (label,button) ->
            val belongs = button.entity==editor.draftId && (button.kind in formKinds ||
                button.kind=="ask" && button.field in setOf("payment","draft_date") ||
                button.command is WorkflowCommand.CancelDraft || button.command is WorkflowCommand.DiscardChanges)
            label to if(belongs) button.copy(editorId=editor.id,editorVersion=editor.revision) else button
        } })
    }

    private fun historyFamily(kind: String): String? = when(kind) {
        "create_draft", "save_draft", "post_draft", "cancel_draft", "discard_changes", "commit_draft" -> "draft"
        "record_transfer", "change_transfer" -> "transfer"
        else -> null
    }
    private fun historyDate(date: String) = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    private fun status(status: DraftStatus) = when(status) { DraftStatus.DRAFT -> "Черновик"; DraftStatus.POSTED -> "Учтена"; DraftStatus.EDITING -> "Есть неучтённые правки"; DraftStatus.CANCELLED -> "Отменена" }
    private fun auditLabel(kind: String) = when(kind) {
        "create_group" -> "Создание группы"; "add_participant" -> "Добавление участника"; "link_self" -> "Привязка Telegram"
        "correct_link" -> "Исправление привязки"; "rename_participant" -> "Изменение имени"; "set_active" -> "Изменение посещения"
        "appoint_organizer" -> "Назначение организатора"; "create_draft" -> "Новый черновик"; "save_draft" -> "Правки тренировки"
        "commit_draft" -> "Сохранение тренировки"; "post_draft" -> "Учёт тренировки"; "cancel_draft" -> "Отмена тренировки"; "discard_changes" -> "Отмена правок"
        "record_transfer" -> "Запись перевода"; "change_transfer" -> "Уточнение перевода"; else -> "Изменение записи"
    }
}
