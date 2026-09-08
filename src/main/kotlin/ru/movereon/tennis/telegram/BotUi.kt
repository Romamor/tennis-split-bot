package ru.movereon.tennis.telegram

import kotlinx.serialization.json.*
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.SqliteAccountingStore
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
        val profiles = service.participants(member, true)
        val names = participantLabels(profiles.map { it.participant })
        val own = profiles.firstOrNull { it.participant.telegramUserId == member.userId }?.participant
        val manager = service.canManage(member)
        fun name(id: String?) = names[id].orEmpty().ifEmpty { "участник" }
        fun amount(value: Long) = if(value > 0) "+$value ₽" else "$value ₽"
        fun draft() = service.draft(member, requireNotNull(action.entity))
        fun summary(content: DraftContent): String {
            val players = content.players.take(12).joinToString("\n") { "${name(it.participantId)}${if(it.plusOne) " +1" else ""}: ${hoursLabel(it.minutes)}" }
            val payments = content.payments.take(10).joinToString(", ") { "${name(it.participantId)} ${it.amount} ₽" }
            return "${content.date}\n${players.ifEmpty { "Игроки пока не указаны" }}\nОплатили: ${payments.ifEmpty { "пока не указано" }}"
        }
        return when(action.kind) {
            "menu" -> Screen("🏓 ${state.group(member.groupId)?.title}\n\n${own?.let { "Твоя запись: ${it.name}" } ?: "Можно связать себя с существующей записью в разделе «Участники»."}",
                listOf(button("Тренировки", BotAction("drafts")), listOf("Балансы" to BotAction("balances"), "Кому перевести?" to BotAction("plan")),
                    button("Отметить перевод", BotAction("new_transfer")), listOf("Участники" to BotAction("participants"), "История" to BotAction("history"))) +
                    if(manager) listOf(button("Восстановить карточки", BotAction("recovery"))) else emptyList())
            "balances" -> Screen("Балансы группы\n\n" + page(profiles, action.page).joinToString("\n") {
                "${it.participant.name}: ${amount(it.balance)}${if(it.participant.active) "" else " · больше не ходит"}"
            } + "\n\nПлюс — внесено сверх своей доли. Минус — осталось внести.",
                pagination(action, profiles.size) + listOf(button("Кому перевести?", BotAction("plan")), button("История", BotAction("history")), home()))
            "plan" -> {
                val plan = service.settlementPlan(member)
                Screen(if(plan.isEmpty()) "Расчёты закрыты." else "Чтобы закрыть текущие расчёты:\n\n" + page(plan, action.page).joinToString("\n") {
                    "${name(it.from.value)} → ${name(it.to.value)}: ${it.amount} ₽"
                } + "\n\nЭто предложение по текущим балансам. После новых расходов суммы могут измениться.",
                    pagination(action, plan.size) + listOf(button("Отметить фактический перевод", BotAction("new_transfer")), home()))
            }
            "participants" -> {
                val visible = profiles.filter { action.showAll || it.participant.active }
                Screen("Участники. Выбери запись, чтобы связать её с собой или посмотреть подробности.",
                    page(visible, action.page).map { button(name(it.participant.id).take(45), BotAction("profile", entity = it.participant.id)) } +
                        pagination(action, visible.size) + listOf(button("Добавить по имени", BotAction("ask", field = "name", back = "profile")),
                            button(if(action.showAll) "Только активные" else "Показать всех", action.copy(showAll = !action.showAll, page = 0)), home()))
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
                rows += button("К участникам", BotAction("participants", showAll = !p.active))
                Screen("${p.name}\nБаланс: ${amount(item.balance)}\n${if(p.active) "Ходит на тренировки" else "Больше не ходит; баланс сохраняется"}\n" +
                    (p.telegramUserId?.let { "Telegram: ${state.name(it)}" } ?: "Telegram пока не привязан"), rows)
            }
            "drafts" -> {
                val drafts = service.drafts(member, action.showAll)
                Screen("Тренировки${if(!action.showAll) " · незавершённые записи" else " · все записи"}\n${if(drafts.isEmpty()) "Пока пусто." else "Можно дополнить существующую запись."}",
                    page(drafts, action.page).map { button("${it.content.date} · ${status(it.status)}", BotAction("draft", entity = it.id)) } +
                        pagination(action, drafts.size) + listOf(button("Новая тренировка", BotAction("create_draft", field = today(member))),
                            button(if(action.showAll) "Только незавершённые" else "Показать все", action.copy(showAll = !action.showAll, page = 0)), home()))
            }
            "draft" -> {
                val d = draft()
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                if(d.status != DraftStatus.CANCELLED) {
                    rows += listOf("Игроки" to BotAction("players", entity = d.id), "Кто оплатил" to BotAction("payments", entity = d.id))
                    rows += listOf("Время всем" to BotAction("time_choices", entity = d.id, version = d.version, field = "all_minutes", draft = d.content, back = "draft"),
                        "Дата" to BotAction("ask", entity = d.id, version = d.version, field = "draft_date", draft = d.content, back = "draft"))
                    rows += button("Посмотреть расчёт", BotAction("preview", entity = d.id))
                    if(manager || member.userId == d.createdBy) {
                        if(d.status == DraftStatus.EDITING) rows += button("Отбросить правки", confirm(WorkflowCommand.DiscardChanges(d.id, d.version), "draft", d.id))
                        rows += button("Отменить тренировку", confirm(WorkflowCommand.CancelDraft(d.id, d.version), "draft", d.id))
                    }
                }
                rows += button("К тренировкам", BotAction("drafts", showAll = true))
                Screen("${status(d.status)}\n${summary(d.content)}" + if(d.status == DraftStatus.EDITING) "\n\nВ балансах пока действует предыдущий учтённый вариант." else "", rows)
            }
            "players" -> {
                val d = draft(); val players = d.content.players.filterNot { it.plusOne }
                Screen("Выбери игрока, чтобы изменить время или добавить +1.", page(players, action.page).map {
                    button("${name(it.participantId).take(40)} · ${hoursLabel(it.minutes)}", BotAction("player", entity = d.id, participant = it.participantId))
                } + pagination(action, players.size) + listOf(button("Добавить игрока", BotAction("add_players", entity = d.id)), button("К тренировке", BotAction("draft", entity = d.id))))
            }
            "add_players" -> {
                val d = draft(); val visible = profiles.filter { (action.showAll || it.participant.active) && d.content.players.none { p -> p.participantId == it.participant.id && !p.plusOne } }
                Screen("Кто ещё играл?", page(visible, action.page).map {
                    val changed = d.content.copy(players = d.content.players + PlayerInput(it.participant.id, 60))
                    button(name(it.participant.id).take(45), apply(WorkflowCommand.SaveDraft(d.id, d.version, changed), "players", d.id))
                } + pagination(action, visible.size) + listOf(button("Показать всех", action.copy(showAll = true, page = 0)),
                    button("Добавить человека по имени", BotAction("ask", field = "name", back = "add_players", entity = d.id)), button("К игрокам", BotAction("players", entity = d.id))))
            }
            "player" -> {
                val d = draft(); val id = requireNotNull(action.participant)
                val player = d.content.players.single { it.participantId == id && !it.plusOne }
                val guest = d.content.players.firstOrNull { it.participantId == id && it.plusOne }
                val rows = mutableListOf(button("Изменить время", BotAction("time_choices", entity = d.id, version = d.version, participant = id, field = "minutes", draft = d.content, back = "player")),
                    button(if(guest == null) "Добавить +1" else "Убрать +1", apply(WorkflowCommand.SaveDraft(d.id, d.version, d.content.withPlusOne(id, guest == null)), "player", d.id, id)))
                if(guest != null) rows += button("Изменить время +1", BotAction("time_choices", entity = d.id, version = d.version, participant = id, field = "minutes", guest = true, draft = d.content, back = "player"))
                rows += button("Убрать игрока", apply(WorkflowCommand.SaveDraft(d.id, d.version, d.content.copy(players = d.content.players.filterNot { it.participantId == id })), "players", d.id))
                rows += button("К игрокам", BotAction("players", entity = d.id))
                Screen("${name(id)}: ${hoursLabel(player.minutes)}${guest?.let { "\n+1: ${hoursLabel(it.minutes)}. Его доля относится на ${name(id)}." } ?: ""}", rows)
            }
            "payments" -> {
                val d = draft(); val visible = profiles.filter { action.showAll || it.participant.active || d.content.payments.any { p -> p.participantId == it.participant.id } }
                Screen("Кто оплачивал стол? Укажи общую оплату каждого человека за эту тренировку.", page(visible, action.page).map {
                    val paid = d.content.payments.firstOrNull { p -> p.participantId == it.participant.id }?.amount ?: 0
                    button("${name(it.participant.id).take(40)} · $paid ₽", BotAction("ask", entity = d.id, version = d.version, participant = it.participant.id, field = "payment", draft = d.content, back = "payments"))
                } + pagination(action, visible.size) + listOf(button("Показать всех", action.copy(showAll = true)), button("К тренировке", BotAction("draft", entity = d.id))))
            }
            "preview" -> {
                val d = draft(); val allocation = calculateTraining(d.content.training())
                val previous = applyEntries(emptyMap(),accounting.history(member.groupId).filter {
                    it.command.entityId == d.id && it.event.kind.startsWith("training_")
                }.flatMap { it.event.entries })
                val current = accounting.balances(member.groupId)
                val updated = applyEntries(current,previous.map { BalanceEntry(it.key,-it.value) } + allocation.entries)
                val affected = (previous.keys + allocation.entries.map { it.participant }).distinct()
                val text = "Всего ${allocation.total} ₽\n\n" + allocation.participantShares.entries.joinToString("\n") { "${name(it.key.value)}: доля ${it.value} ₽" } +
                    "\n\nБалансы после сохранения:\n" + affected.joinToString("\n") { "${name(it.value)}: ${amount(current.getOrDefault(it,0))} → ${amount(updated.getOrDefault(it,0))}" }
                val rows = mutableListOf<List<Pair<String,BotAction>>>()
                if(d.status in setOf(DraftStatus.DRAFT,DraftStatus.EDITING) && (manager || member.userId == d.createdBy))
                    rows += button(if(d.financialVersion == 0L) "Учесть тренировку" else "Сохранить изменения", apply(WorkflowCommand.PostDraft(d.id,d.version), "draft", d.id))
                rows += button("Исправить", BotAction("draft", entity = d.id))
                Screen(text.take(3800), rows)
            }
            "time_choices" -> {
                val content=requireNotNull(action.draft)
                checkAccounting(content.players.isNotEmpty(),ErrorCode.INVALID_INPUT,"Pick players first")
                val page = action.page.coerceAtLeast(0)
                val choices = (1L..6L).map { index ->
                    val minutes = (page.toLong() * 6 + index) * 30
                    val changed=content.copy(players=content.players.map {
                        if(action.field=="all_minutes" || it.participantId==action.participant && it.plusOne==action.guest) it.copy(minutes=minutes) else it
                    })
                    hoursLabel(minutes) to apply(WorkflowCommand.SaveDraft(requireNotNull(action.entity),requireNotNull(action.version),changed),action.back ?: "draft",action.entity,action.participant)
                }
                val navigation = buildList {
                    if(page > 0) add("← Меньше" to action.copy(page=page-1))
                    if(page < Int.MAX_VALUE) add("Больше →" to action.copy(page=page+1))
                }
                Screen("Сколько времени играли? Выбери с шагом полчаса.",choices.chunked(3) + listOf(navigation,
                    button("Назад",BotAction(action.back ?: "draft",entity=action.entity,participant=action.participant))))
            }
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
                val audit = service.audit(member).reversed()
                val rows = page(audit,action.page).map { button("${it.recordedAt.take(10)} · ${auditLabel(it.kind)}", BotAction("audit", entity = it.id.toString())) }
                Screen("История действий\n${if(audit.isEmpty()) "Пока пусто." else "Здесь видно, кто вносил и исправлял записи."}", rows + pagination(action,audit.size) + listOf(home()))
            }
            "audit" -> {
                val event = service.audit(member).single { it.id.toString() == action.entity }
                fun readable(text: String?): String {
                    if(text == null) return "Не было"
                    val value = Json.parseToJsonElement(text).jsonObject
                    val content = value["content"]?.jsonObject
                    if(content != null) return summary(Json.decodeFromJsonElement<DraftContent>(content)).take(1400)
                    if(value["name"] != null) return "Имя: ${value["name"]!!.jsonPrimitive.content}\nTelegram: ${value["telegramUserId"]?.jsonPrimitive?.longOrNull?.let(state::name) ?: "не привязан"}\nХодит: ${if(value["active"]?.jsonPrimitive?.booleanOrNull == true) "да" else "нет"}"
                    if(value["amount"] != null) return "${name(value["from"]?.jsonPrimitive?.content)} → ${name(value["to"]?.jsonPrimitive?.content)}: ${value["amount"]?.jsonPrimitive?.content} ₽"
                    return auditLabel(event.kind)
                }
                Screen("${auditLabel(event.kind)}\nАвтор: ${state.name(event.actorUserId)}\n${event.recordedAt.take(19)} UTC\n\nДо:\n${readable(event.beforeJson)}\n\nПосле:\n${readable(event.afterJson)}",
                    listOf(button("К истории",BotAction("history"))))
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
    }

    private fun status(status: DraftStatus) = when(status) { DraftStatus.DRAFT -> "Черновик"; DraftStatus.POSTED -> "Учтена"; DraftStatus.EDITING -> "Есть неучтённые правки"; DraftStatus.CANCELLED -> "Отменена" }
    private fun auditLabel(kind: String) = when(kind) {
        "create_group" -> "Создание группы"; "add_participant" -> "Добавление участника"; "link_self" -> "Привязка Telegram"
        "correct_link" -> "Исправление привязки"; "rename_participant" -> "Изменение имени"; "set_active" -> "Изменение посещения"
        "appoint_organizer" -> "Назначение организатора"; "create_draft" -> "Новый черновик"; "save_draft" -> "Правки тренировки"
        "post_draft" -> "Учёт тренировки"; "cancel_draft" -> "Отмена тренировки"; "discard_changes" -> "Отмена правок"
        "record_transfer" -> "Запись перевода"; "change_transfer" -> "Уточнение перевода"; else -> "Изменение записи"
    }
}
