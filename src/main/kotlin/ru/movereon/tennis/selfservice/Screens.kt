package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.telegram.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All lists have an explicit page, including the text above their buttons. */
class Screens(private val service: SettlementService, private val state: InteractionStore, private val botName: String) {
    data class Output(val text: String, val keyboard: TgKeyboard, val tokens: Set<String>)
    private fun name(id: Long): String = service.account(id).let { u ->
        clean(u.name, 36) + (u.username?.let { " · @${clean(it, 32)}" } ?: "")
    }
    private fun shortName(id: Long) = clean(service.account(id).name, 36)
    fun render(action: ScreenAction, access: Access?, scope: String, user: Long?, form: InputForm? = null, notice: String? = null, inGroup: Boolean = false, telegramAdmins:Set<Long> = emptySet()): Output = state.buttonBatch {
        renderInside(action,access,scope,user,form,notice,inGroup,telegramAdmins)
    }
    private fun renderInside(action: ScreenAction, access: Access?, scope: String, user: Long?, form: InputForm?, notice: String?, inGroup: Boolean, telegramAdmins:Set<Long>):Output {
        val rows = mutableListOf<List<TgButton>>()
        val tokens = mutableSetOf<String>()
        fun button(label: String, next: ScreenAction): TgButton {
            if (inGroup && next.kind in privateActions) {
                val token = state.button(next, null, "private-link:${next.group}:${next.id}", permanent = true)
                return TgButton(label, url = "https://t.me/$botName?start=n_$token")
            }
            val token = state.button(next, user, scope)
            tokens += token
            return TgButton(label, callbackData = "n:$token")
        }
        fun next(kind: String, id: String = action.id, page: Int = 0, target: Long = action.user, value: Long = 0, version: Long = action.version, option: String = action.option) =
            ScreenAction(kind, action.group, id, page, target, value, version, option,back=action.back,resume=action.resume)
        fun row(label: String, next: ScreenAction) { rows += listOf(button(label, next)) }
        fun pages(index: Int, total: Int, base: ScreenAction = action) {
            if (total > 1) rows += buildList<TgButton> {
                if (index > 0) add(button("‹ Назад", base.copy(page = index - 1)))
                add(button("${index + 1} / $total", base.copy(page = index)))
                if (index + 1 < total) add(button("Дальше ›", base.copy(page = index + 1)))
            }
        }
        fun menu() { row("В меню группы", ScreenAction("menu",action.group)) }
        fun back(fallback:ScreenAction) { row("⬅️ Назад",action.back ?: fallback) }
        val group = if (action.group < 0) service.group(action.group) else null
        val a = access
        val accounts=if(a!=null) service.groupAccounts(a) else emptyList()
        val collisions=accounts.groupBy { clean(it.name,36).lowercase() }.filterValues { it.size>1 }
        val labels=accounts.associate { account ->
            account.id to (clean(account.name,36)+if(clean(account.name,36).lowercase() in collisions && !account.username.isNullOrBlank()) " · @${clean(account.username,32)}" else "")
        }
        val ambiguous=labels.entries.groupBy { it.value.lowercase() }.filterValues { it.size>1 }.values.flatten().map { it.key }.toSet()
        fun label(id:Long)=labels[id] ?: shortName(id)
        fun personRow(id:Long,text:String,target:ScreenAction) {
            val current=if(action.kind=="add_players" && form!=null) action.copy(option=state.formSignature(form)) else action
            val destination=if(id in ambiguous) ScreenAction("profile_preview",action.group,action.id,page=action.page,user=id,back=current,resume=target) else target
            row(text+(if(id in ambiguous) " · #${accounts.filter { labels[it.id]==labels[id] }.sortedBy { it.id }.indexOfFirst { it.id==id }+1}" else ""),destination)
        }
        val text = when (action.kind) {
            "profile_preview" -> {
                rows+=listOf(TgButton("Открыть профиль Telegram",url="tg://user?id=${action.user}"))
                row("Выбрать этого участника",requireNotNull(action.resume))
                back(next("roster",option="players"))
                "${name(action.user)}\nВ списке есть одинаковые имена. Проверь профиль перед выбором."
            }
            "exit_confirm" -> {
                row("Продолжить ввод",next("exit_continue"))
                row("Сбросить и выйти",next("exit_discard"))
                "Есть несохранённые изменения. Сбросить их и выйти?"
            }
            "groups" -> {
                val p = service.groups(requireNotNull(user), action.page)
                p.items.forEach { row(clean(it.title, 60), ScreenAction("menu", it.id)) }
                if (p.total > 0) pages(p.index, p.pages, action.copy(group = p.items.first().id))
                "Выбери группу для расчётов." + if (p.total == 0) "\nДобавь бота в группу и отправь там /start. Затем открой личный чат снова." else ""
            }
            "menu" -> {
                requireNotNull(a)
                row("🏓 Мои тренировки", next("trainings", option = "mine"))
                row("💰 Мои расчёты", next("debts"))
                if (service.isAdmin(a)) {
                    row("➕ Создать тренировку", next("new"))
                    row("📋 Управление тренировками", next("trainings", option = "all"))
                }
                if (a.telegramAdmin) row("👥 Администраторы бота", next("administrators", option = ""))
                if(service.groups(a.userId).total>1) row("🔄 Сменить группу", ScreenAction("groups",action.group))
                "Что хочешь сделать?"
            }
            "trainings" -> {
                val p = service.trainings(requireNotNull(a), action.page, mine = action.option == "mine", unfinished = action.option == "open")
                p.items.forEach { row("${date(it.date)} · ${clean(it.title, 28)} · ${phase(it.phase)}", next("training", it.id, option = "").copy(back=action.copy(page=p.index))) }
                pages(p.index, p.pages)
                menu()
                (if (action.option == "mine") "Мои тренировки" else "Тренировки группы") + " · ${p.total}" + if (p.total == 0) "\nЗаписей пока нет." else ""
            }
            "training", "public" -> {
                val t = service.training(requireNotNull(a), action.id)
                val p = t.players.filter { it.playing || it.paid > 0 }.sortedBy { it.ordinal }
                val index = action.page.coerceIn(0, maxOf(0, (p.size - 1) / 8))
                val body = buildString {
                    append("${clean(t.title, 100)} · ${date(t.date)} · ${t.startTime}\n${phase(t.phase)}\n")
                    append("Играли: ${p.count { it.playing }} · гостей +1: ${p.count { it.guestMinutes > 0 }}\n")
                    append("Оплачено: ${p.sumOf { it.paid }} ₽\n\n")
                    p.drop(index * 8).take(8).forEach { append(playerLine(it)).append('\n') }
                    if (p.isEmpty()) append("Пока никто не отметился.\n")
                    if (p.size > 8) append("Состав · ${index + 1} / ${(p.size + 7) / 8}\n")
                    if (t.phase == TrainingPhase.REVIEW) append("\nПока правки не применены, действует прежний расчёт.")
                }
                if (action.kind == "public") {
                    val participation=button("Участие", next("player", target = 0))
                    val link = state.button(next("training"), null, "link:${t.groupId}:${t.id}", permanent = true)
                    rows += listOf(participation,TgButton("Открыть у бота", url = "https://t.me/$botName?start=n_$link"))
                } else {
                    pages(index, maxOf(1, (p.size + 7) / 8))
                    val participationLabel = when {
                        t.phase !in setOf(TrainingPhase.OPEN, TrainingPhase.REVIEW) -> "Участие"
                        t.players.any { it.userId == a.userId && it.playing } -> "Изменить участие"
                        else -> "Играл"
                    }
                    row(participationLabel, next("player", target = a.userId).copy(back=action.copy(page=index)))
                    if (service.isAdmin(a)) {
                        if (state.delivery("training:${t.groupId}:${t.id}")?.status in setOf("UNKNOWN", "FAILED"))
                            row("Восстановить сообщение в группе", next("recover_confirm").copy(back=action.copy(page=index)))
                        if (t.phase in setOf(TrainingPhase.OPEN, TrainingPhase.REVIEW)) {
                            row("Игроки", next("roster", option = "players").copy(back=action.copy(page=index)))
                            row("Изменить название и время", next("edit_details", version = t.version).copy(back=action.copy(page=index)))
                            row(if(t.phase==TrainingPhase.REVIEW) "✅ Применить правки" else "🧮 Учесть тренировку", next("preview_finish", version = t.version).copy(back=action.copy(page=index)))
                        }
                        if (t.phase == TrainingPhase.CLOSED) row("✏️ Исправить тренировку", next("reopen", version = t.version))
                        if (t.phase != TrainingPhase.CANCELLED) row("🗑 Отменить тренировку", next("cancel_confirm", version = t.version).copy(back=action.copy(page=index)))
                    }
                    row("История изменений", next("history").copy(back=action.copy(page=index)))
                    back(ScreenAction("trainings",action.group,option=if(service.isAdmin(a)) "all" else "mine"))
                    menu()
                }
                body
            }
            "player" -> {
                val t = service.training(requireNotNull(a), action.id)
                val target = action.user.takeIf { it > 0 } ?: a.userId
                checkAccounting(target == a.userId || service.isAdmin(a), ErrorCode.FORBIDDEN, "Можно менять только свои данные")
                val stored = t.players.firstOrNull { it.userId == target }
                val draft = state.attendanceDraft(a.userId,a.groupId)?.takeIf { it.training==t.id && it.user==target }
                val player = draft?.value ?: stored
                if (t.phase in setOf(TrainingPhase.OPEN, TrainingPhase.REVIEW)) {
                    fun change(label: String, type: AttendanceChange, value: Long = 0) = button(label, next("change", target = target, value = value, option = type.name))
                    if (player?.playing != true) rows += listOf(change("Играл · 1 ч", AttendanceChange.JOIN))
                    else {
                        rows += listOf(change("−0,5 ч", AttendanceChange.ADJUST_MINUTES, -30), change("+0,5 ч", AttendanceChange.ADJUST_MINUTES, 30))
                        rows += listOf(change(if (player.guestMinutes > 0) "Убрать гостя +1" else "Добавить гостя +1", AttendanceChange.GUEST, if (player.guestMinutes > 0) 0 else 1))
                        if (player.guestMinutes > 0) rows += listOf(
                            change("Гость −0,5 ч", AttendanceChange.SET_GUEST_MINUTES, (player.guestMinutes - 30).coerceAtLeast(30)),
                            change("Гость +0,5 ч", AttendanceChange.SET_GUEST_MINUTES, Math.addExact(player.guestMinutes, 30)))
                    }
                    if (player?.playing == true) {
                        if (player.paid == 0L) rows += listOf(change("Платил · 300 ₽", AttendanceChange.MARK_PAID))
                        else rows += listOf(change("−50 ₽", AttendanceChange.ADJUST_PAID, -50), change("+50 ₽", AttendanceChange.ADJUST_PAID, 50))
                        if (!inGroup) row("Другая сумма", next("ask_paid", target = target))
                        if (player.paid > 0) row("Не играл", next("leave_confirm", target = target))
                        else rows += listOf(change("Не играл", AttendanceChange.LEAVE))
                    }
                    if (draft != null) {
                        if (draft.expected==stored) row("Всё правильно",next("save_attendance",target=target,option=state.draftSignature(draft)))
                        else {
                            row("Загрузить сохранённые данные",next("reload_attendance",target=target))
                            row("Убрать несохранённый ввод",next("discard_attendance",target=target,option=state.draftSignature(draft)))
                        }
                    }
                } else if (draft!=null) {
                    row("Убрать несохранённый ввод",next("discard_attendance",target=target,option=state.draftSignature(draft)))
                } else if (inGroup) row("Закрыть",next("close_panel"))
                if(draft?.returnPage!=null) row("К составу",draft.origin ?: next("roster",page=draft.returnPage,target=0,option="players"))
                else row("Карточка тренировки", draft?.origin ?: action.back ?: next("training", target = 0, option = ""))
                "${clean(t.title, 60)} · ${date(t.date)}\n${name(target)}\n" +
                    (if (player?.playing == true) "Играл ${hours(player.minutes)}" else "Не играл") +
                    (if ((player?.guestMinutes ?: 0) > 0) " · +1 ${hours(player!!.guestMinutes)}" else "") +
                    "\nОплатил стол: ${player?.paid ?: 0} ₽" +
                    (if (draft!=null && draft.expected!=stored) "\nСохранённые данные уже изменились. Обнови форму перед подтверждением." else if (draft!=null) "\nСохраним после «Всё правильно»." else "") +
                    if (t.phase == TrainingPhase.CLOSED) "\nТренировка учтена. Исправить данные может администратор через «Исправить тренировку»." else ""
            }
            "leave_confirm" -> {
                val t = service.training(requireNotNull(a), action.id)
                val target = action.user.takeIf { it > 0 } ?: a.userId
                checkAccounting(target == a.userId || service.isAdmin(a), ErrorCode.FORBIDDEN, "Можно менять только свои данные")
                val pending=state.attendanceDraft(a.userId,a.groupId)?.takeIf { it.training==t.id && it.user==target }
                val p = requireNotNull(pending?.value ?: t.players.firstOrNull { it.userId == target }) { "Участник не найден в тренировке" }
                row(if (p.paid > 0) "Не играл — убрать ${p.paid} ₽" else "Подтвердить: не играл",
                    next("change", target = target, value = p.paid,
                        option = if (p.paid > 0) AttendanceChange.LEAVE_AND_CLEAR_PAYMENT.name else AttendanceChange.LEAVE.name))
                row("Назад", next("player", target = target))
                "${name(target)}\nНе играл в этой тренировке?\n" +
                    if (p.paid > 0) "Участие${if (p.guestMinutes > 0) " и гость +1" else ""} будут убраны вместе с оплатой стола ${p.paid} ₽. Переводы между людьми сохранятся."
                    else "Участие будет убрано."
            }
            "roster" -> {
                val t=service.training(requireNotNull(a),action.id)
                checkAccounting(service.isAdmin(a),ErrorCode.FORBIDDEN,"Доступно администратору группы")
                val players=t.players.filter { it.playing }.sortedBy { it.ordinal }
                val index=action.page.coerceIn(0,maxOf(0,(players.size-1)/8))
                players.drop(index*8).take(8).forEach { p ->
                    personRow(p.userId,"${label(p.userId)} · ${hours(p.minutes)}${if(p.guestMinutes>0) " · +1" else ""} · ${p.paid} ₽",
                        next("player",page=index,target=p.userId,option="roster").copy(back=action.copy(page=index)))
                }
                pages(index,maxOf(1,(players.size+7)/8))
                row("Участники группы",next("add_players",option="").copy(back=action.copy(page=index)))
                back(next("training",option=""))
                "${clean(t.title,60)} · ${date(t.date)}\nИгроки: ${players.size} · гостей: ${players.count { it.guestMinutes>0 }}\nОплачено: ${players.sumOf { it.paid }} ₽\nВыбери игрока для правки или добавь людей через «Участники группы»."
            }
            "add_players" -> {
                val t=service.training(requireNotNull(a),action.id)
                checkAccounting(service.isAdmin(a),ErrorCode.FORBIDDEN,"Доступно администратору группы")
                val f=requireNotNull(form)
                val playing=t.players.filter { it.playing }.map { it.userId }.toSet()
                val order=f.order ?: service.rosterIds(a).filter { it !in playing }
                val index=action.page.coerceIn(0,maxOf(0,(order.size-1)/8))
                val p=Page(order.drop(index*8).take(8).map { service.groupAccount(a,it) },order.size,index)
                val selected=f.selectedUsers.filter { it !in playing }.toSet()
                val signature=state.formSignature(f)
                p.items.forEach { person ->
                    personRow(person.id,"${if(person.id in playing) "✓ Уже в составе ·" else if(person.id in selected) "✅" else "▫️"} ${label(person.id)}",
                        next("toggle_player",page=p.index,target=person.id,option=signature))
                }
                pages(p.index,p.pages,action.copy(option=signature))
                if(selected.isNotEmpty()) row("Добавить · ${selected.size}",next("save_players",page=p.index,version=t.version,option=signature))
                row("Добавить человека",next("pick_players",page=p.index,option=signature))
                row("Отмена",f.origin ?: next("roster",option="players"))
                "${clean(t.title,60)} · ${date(t.date)}\nУчастники группы · выбрано: ${selected.size}\nОтметь нескольких людей на любых страницах. Добавим по 1 ч и 0 ₽ после подтверждения."
            }
            "transfer_people" -> {
                val p=service.roster(requireNotNull(a),action.page,exclude=setOf(a.userId))
                p.items.forEach { person ->
                    personRow(person.account.id,label(person.account.id),next("transfer_direction",target=person.account.id).copy(back=action.copy(page=p.index)))
                }
                pages(p.index,p.pages); back(ScreenAction("debts",action.group)); "С кем рассчитываемся?"
            }
            "administrators", "admin_candidates" -> {
                val p=service.administrators(requireNotNull(a),telegramAdmins,action.kind=="admin_candidates",action.page)
                p.items.forEach { entry ->
                    val role=when(entry.role) { GroupRole.SUPERADMIN -> "Суперадмин · Telegram";GroupRole.ADMIN -> "Админ бота";GroupRole.MEMBER -> "Участник" }
                    personRow(entry.account.id,"${label(entry.account.id)} · $role",next("admin_person",target=entry.account.id).copy(back=action.copy(page=p.index)))
                }
                pages(p.index,p.pages)
                if (action.kind=="administrators") {
                    row("Назначить администратора",next("admin_candidates"))
                    menu()
                } else back(next("administrators"))
                if (action.kind=="administrators") "Администраторы · ${p.total}\nСуперадмины получают права из Telegram. Назначенные админы бота управляют тренировками, но не назначают других."
                else "Кого назначить?\nВ этом списке только участники без административной роли."
            }
            "admin_person" -> {
                requireNotNull(a)
                checkAccounting(a.telegramAdmin, ErrorCode.FORBIDDEN, "Назначать могут администраторы Telegram-группы")
                val appointed = service.isAdmin(Access(a.groupId, action.user))
                val superior=action.user in telegramAdmins
                if (appointed) row(if(superior) "Снять дополнительное назначение" else "Снять назначение",next("set_admin",value=0))
                else if (!superior) row("Назначить администратором",next("set_admin",value=1))
                row("Назад", next("administrators"))
                "${name(action.user)}\n" + when {
                    superior -> "Суперадминистратор · администратор Telegram-группы.\nСтаршие права меняются в настройках группы." + if (appointed) "\nТакже есть отдельное назначение администратором бота." else ""
                    appointed -> "Администратор бота · назначен в этой группе. Можно снять назначение."
                    else -> "Участник · административных прав нет."
                }
            }
            "preview_finish" -> {
                val t = service.training(requireNotNull(a), action.id)
                checkAccounting(service.isAdmin(a), ErrorCode.FORBIDDEN, "Доступно администратору группы")
                val calc = calculateTraining(t.calculation())
                val p = calc.entries
                val index = action.page.coerceIn(0, maxOf(0, (p.size - 1) / 8))
                pages(index, maxOf(1, (p.size + 7) / 8))
                row(if(t.phase==TrainingPhase.REVIEW) "✅ Подтвердить правки" else "✅ Подтвердить учёт", next("finish", version = t.version))
                back(next("training"))
                "Проверка расчёта · ${date(t.date)}\nВсего: ${calc.total} ₽\n\n" + p.drop(index * 8).take(8).joinToString("\n") {
                    "${shortName(it.participant.value.toLong())}: ${signed(it.amount)} ₽"
                } + "\n\nЭто результат этой тренировки. При применении правок он заменит прежний."
            }
            "cancel_confirm" -> {
                row("Да, отменить тренировку", next("cancel"))
                back(next("training"))
                "Отменить тренировку? Её влияние на балансы будет снято. Реальные переводы сохранятся."
            }
            "recover_confirm" -> {
                row("Опубликовать карточку заново", next("recover_card"))
                back(next("training"))
                "Если карточки в группе нет, её можно опубликовать заново. При неизвестном результате прежней отправки в группе могла остаться старая копия. Данные тренировки не дублируются."
            }
            "balances", "settled" -> {
                val p = service.roster(requireNotNull(a), action.page, balanceOnly = true, settled = action.kind == "settled")
                pages(p.index, p.pages)
                if(action.kind=="settled") row("👥 Баланс группы",action.back?.takeIf { it.kind=="balances" } ?: ScreenAction("balances",action.group,back=ScreenAction("debts",action.group)))
                else row("⚖️ Нулевой баланс",next("settled").copy(back=action))
                back(ScreenAction("debts",action.group))
                (if(action.kind=="settled") "⚖️ Нулевой баланс" else "👥 Баланс группы")+" · ${p.total}\n\n"+
                    p.items.joinToString("\n") { "${clean(it.account.name,48)} · баланс: ${signed(it.balance)} ₽" }
            }
            "debts" -> {
                requireNotNull(a)
                val balances = service.balances(a)
                val transfers = suggestTransfers(balances.mapKeys { ParticipantId(it.key.toString()) }).filter { it.from.value == a.userId.toString() || it.to.value == a.userId.toString() }
                val index = action.page.coerceIn(0, maxOf(0, (transfers.size - 1) / 8))
                transfers.drop(index * 8).take(8).forEach {
                    val from = it.from.value.toLong(); val to = it.to.value.toLong()
                    row("${if (from == a.userId) "Я → ${shortName(to)}" else "${shortName(from)} → я"}: ${it.amount} ₽",
                        next("suggested_transfer", target = if (from == a.userId) to else from, value = it.amount, option = if (from == a.userId) "out" else "in").copy(back=action))
                }
                pages(index, maxOf(1, (transfers.size + 7) / 8))
                row("💸 Записать перевод", next("transfer_people").copy(back=action))
                row("🧾 История переводов",next("transfers").copy(back=action))
                row("👥 Баланс группы",next("balances").copy(back=action))
                menu()
                "Мой баланс: ${signed(balances[a.userId] ?: 0)} ₽\n" + if (transfers.isEmpty()) "Сейчас рассчитываться не с кем." else "Предлагаемые переводы для взаиморасчётов. Отмечай перевод после передачи денег."
            }
            "transfers" -> {
                val p = service.transfers(requireNotNull(a), action.page)
                p.items.forEach { row("${date(it.date)} · ${it.amount} ₽ · ${if (it.from == a.userId) "${shortName(it.to)} ←" else "${shortName(it.from)} →"}", next("transfer", it.id).copy(back=action.copy(page=p.index))) }
                pages(p.index, p.pages); back(ScreenAction("debts",action.group))
                "Мои переводы · ${p.total}"
            }
            "transfer" -> {
                val t = service.transfer(requireNotNull(a), action.id)
                checkAccounting(a.userId in setOf(t.from, t.to) || service.isAdmin(a), ErrorCode.FORBIDDEN, "Перевод доступен его сторонам")
                if (a.userId in setOf(t.from, t.to)) {
                    if(t.status!=PaymentStatus.CANCELLED) row("✏️ Исправить сумму",next("edit_transfer_amount",version=t.version).copy(back=action))
                    if (t.status == PaymentStatus.ACTIVE) row("Уточнить перевод", next("review_transfer", version = t.version))
                    if (t.status == PaymentStatus.REVIEW && t.reviewer == a.userId) {
                        row("Всё верно", next("confirm_transfer", version = t.version))
                        row("Отменить запись", next("cancel_transfer", version = t.version))
                    }
                }
                row("История изменений",next("transfer_history").copy(back=action))
                back(ScreenAction("transfers",action.group,back=ScreenAction("debts",action.group)))
                "${name(t.from)} → ${name(t.to)}\n${t.amount} ₽ · ${date(t.date)}\n" + when (t.status) {
                    PaymentStatus.ACTIVE -> "Учтён"
                    PaymentStatus.REVIEW -> "Уточняем · пока не влияет на баланс"
                    PaymentStatus.CANCELLED -> "Отменён"
                } + if (t.note.isNotBlank()) "\n${clean(t.note, 300)}" else ""
            }
            "transfer_direction" -> {
                row("Я отправил → ${shortName(action.user)}", next("transfer_amount", option = "out").copy(back=action))
                row("Я получил ← ${shortName(action.user)}", next("transfer_amount", option = "in").copy(back=action))
                back(next("transfer_people")); "Кто кому передал деньги?"
            }
            "form" -> {
                val f = requireNotNull(form)
                fun formAction(kind: String) = next(kind, option = state.formSignature(f))
                fun similarText() = if(f.similar.isEmpty()) "" else "\nРанее записано:\n"+f.similar.take(2).joinToString("\n") {
                    val t=service.transfer(requireNotNull(a),it)
                    "${shortName(t.from)} → ${shortName(t.to)} · ${t.amount} ₽ · ${date(t.date)}"
                }
                when (f.kind) {
                    "title" -> { row("Оставить «${clean(f.title, 30)}»", formAction("form_next")); "Напиши название тренировки." }
                    "date" -> { row("${date(f.date)}", formAction("form_next")); "Напиши дату: ДД.ММ.ГГГГ." }
                    "time" -> { row("${f.time}", formAction("form_next")); "Во сколько начало? Напиши ЧЧ:ММ." }
                    "ready" -> {
                        row(if (f.training.isEmpty()) "Опубликовать в группе" else "Сохранить изменения", formAction("save_training"))
                        row("Изменить название / время", formAction("form_restart"))
                        "${clean(f.title, 100)}\n${date(f.date)} · ${f.time}\n" + if (f.training.isEmpty()) "После публикации участники смогут отмечаться." else "Данные изменятся в существующей тренировке."
                    }
                    "paid" -> "${name(f.user)}\nНапиши общую сумму оплаты стола в рублях. Можно 0."
                    "edit_transfer_amount" -> "Текущая сумма: ${f.amount} ₽\nНапиши исправленную сумму в рублях."
                    "edit_transfer_ready", "edit_transfer_duplicate" -> {
                        row("✅ Сохранить сумму",formAction("save_transfer_amount"))
                        (if(f.kind=="edit_transfer_duplicate") "Похожий перевод за последние 24 часа уже есть. Подтвердить исправление?\n" else "Проверь исправленную сумму:\n")+"${f.amount} ₽"+similarText()
                    }
                    "transfer_amount" -> "${if (f.direction == "out") "Я → ${name(f.user)}" else "${name(f.user)} → я"}\nНапиши сумму уже переданных денег в целых рублях."
                    "transfer_ready", "transfer_duplicate" -> {
                        row(if (f.kind == "transfer_duplicate") "Это ещё один перевод — записать" else "Деньги переданы — записать", formAction("save_transfer"))
                        if (f.kind == "transfer_ready") row("Изменить дату", formAction("transfer_date"))
                        if (f.kind == "transfer_ready") row("Комментарий", formAction("transfer_note"))
                        (if (f.kind == "transfer_duplicate") "Похожий перевод уже записан за последние 24 часа. Это ещё один перевод?\n\n" else "") +
                            "${if (f.direction == "out") "Я → ${name(f.user)}" else "${name(f.user)} → я"}\n${f.amount} ₽ · ${date(f.date)}" + if (f.note.isNotBlank()) "\n${clean(f.note, 300)}"+similarText() else similarText()
                    }
                    "transfer_date" -> "Напиши дату перевода: ДД.ММ.ГГГГ."
                    "transfer_note" -> "Напиши комментарий к переводу, не длиннее 300 символов."
                    "pick_account", "pick_players" -> "Выбери реальный аккаунт кнопкой под строкой ввода. Он появится в составе этой группы."
                    else -> error("Unknown input form")
                }.also {
                    if(f.kind=="pick_players") row("Назад к выбору",next("add_players",id=f.training,page=f.page,option=state.formSignature(f)))
                    else row("Отмена",f.origin ?: next(if (f.training.isNotEmpty()) "training" else "debts", id = f.training))
                }
            }
            "history", "transfer_history" -> {
                val p = service.history(requireNotNull(a), action.id.takeIf { it.isNotEmpty() && action.kind=="history" }, action.page,action.id.takeIf { action.kind=="transfer_history" })
                pages(p.index, p.pages)
                back(next(if(action.kind=="transfer_history") "transfer" else if (action.id.isEmpty()) "menu" else "training"))
                "История изменений · ${p.total}\n\n" + p.items.joinToString("\n\n") { "${Instant.parse(it.occurredAt).atZone(ZoneId.of(requireNotNull(group).timeZone)).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} · ${shortName(it.actorId)}\n${describe(it).take(330)}" }
            }
            else -> error("Unknown screen: ${action.kind}")
        }
        if (inGroup && action.kind !in setOf("public","player","exit_confirm")) row("Закрыть", next("close_panel"))
        val header = if (group != null && action.kind != "groups") "${clean(group.title, 80)}\n\n" else ""
        // No arbitrary user content can grow a Telegram message beyond the documented limit.
        val result = (notice?.let { "${clean(it, 220)}\n\n" } ?: "") + header + text
        check(result.length <= 4096) { "Экран превышает допустимую длину" }
        return Output(result, TgKeyboard(rows), tokens)
    }
    private fun playerLine(p: Attendance) = "${name(p.userId)}\n  ${if (p.playing) hours(p.minutes) else "Не играл"}" +
        (if (p.guestMinutes > 0) " · +1 ${hours(p.guestMinutes)}" else "") + " · оплатил ${p.paid} ₽"
    private fun describe(a: AuditAction): String = when (a.kind) {
        "CreateTraining" -> "Опубликовал тренировку"
        "EditTraining" -> {
            val after=state.json.decodeFromString<TrainingRecord>(a.after)
            val before=state.json.decodeFromString<TrainingRecord>(requireNotNull(a.before))
            listOfNotNull(if(before.title!=after.title) "Название: ${clean(before.title,100)} → ${clean(after.title,100)}" else null,
                if(before.date!=after.date) "Дата: ${date(before.date)} → ${date(after.date)}" else null,
                if(before.startTime!=after.startTime) "Начало: ${before.startTime} → ${after.startTime}" else null).joinToString("\n")
        }
        "EditTransferAmount" -> {
            val before=state.json.decodeFromString<MoneyTransfer>(requireNotNull(a.before))
            val after=state.json.decodeFromString<MoneyTransfer>(a.after)
            "Исправил сумму перевода: ${before.amount} ₽ → ${after.amount} ₽"
        }
        "FinishTraining" -> "Учёл тренировку · ${state.json.decodeFromString<TrainingRecord>(a.after).players.sumOf { it.paid }} ₽"
        "ReopenTraining" -> "Открыл исправление тренировки; прежний расчёт сохранён"
        "CancelTraining" -> "Отменил тренировку и снял её расчёт"
        "RecordTransfer" -> "Записал перевод"
        "ChangeTransfer" -> "Изменил состояние перевода"
        "SetAdministrator" -> if (a.after == "true") "Назначил администратора бота" else "Снял назначение администратора"
        "AddPlayers" -> {
            val after=state.json.decodeFromString<TrainingRecord>(a.after)
            val before=a.before?.let { state.json.decodeFromString<TrainingRecord>(it) }
            val added=after.players.filter { p -> p.playing && before?.players?.any { it.userId==p.userId && it.playing }!=true }
            "Добавил игроков: ${added.size} · "+added.take(3).joinToString(", ") { shortName(it.userId) }+if(added.size>3) ", ещё ${added.size-3}" else ""
        }
        "ChangeAttendance", "SaveAttendance" -> {
            val after = state.json.decodeFromString<TrainingRecord>(a.after)
            val before = a.before?.let { state.json.decodeFromString<TrainingRecord>(it) }
            val changed = after.players.filter { p -> before?.players?.find { it.userId == p.userId } != p }
            changed.joinToString("\n") { p ->
                val old=before?.players?.find { it.userId==p.userId }
                "${shortName(p.userId)}:\n"+listOfNotNull(
                    if(old?.playing!=p.playing) "Участие: ${if(old?.playing==true) "играл" else "не отмечено"} → ${if(p.playing) "играл" else "не играл"}" else null,
                    if(old==null || old.minutes!=p.minutes) "Время: ${old?.let { hours(it.minutes) } ?: "—"} → ${hours(p.minutes)}" else null,
                    if(old?.guestMinutes!=p.guestMinutes && (old?.guestMinutes ?: 0)>0 || p.guestMinutes>0 && old?.guestMinutes!=p.guestMinutes) "Гость +1: ${hours(old?.guestMinutes ?: 0)} → ${hours(p.guestMinutes)}" else null,
                    if(old==null || old.paid!=p.paid) "Оплата: ${old?.paid ?: 0} ₽ → ${p.paid} ₽" else null).joinToString("\n")
            }.ifEmpty { "Подтвердил прежние время и оплату" }
        }
        else -> "Изменение записи"
    }
    companion object {
        val privateActions = setOf("menu", "groups", "trainings", "debts", "balances", "settled", "transfers", "transfer", "transfer_people", "transfer_direction", "transfer_amount", "new", "edit_details", "profile_preview", "ask_paid", "edit_transfer_amount", "save_transfer_amount", "pick_account", "pick_players", "add_players", "toggle_player", "save_players", "roster", "administrators", "admin_candidates", "admin_person", "set_admin", "history", "transfer_history")
        fun clean(text: String, length: Int) = text.replace(Regex("[\\r\\n\\t]"), " ").take(length)
        fun hours(minutes: Long) = "${minutes / 60}${if (minutes % 60 == 30L) ",5" else ""} ч"
        fun date(value: String) = LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        fun phase(value: TrainingPhase) = when (value) {
            TrainingPhase.OPEN -> "Открыта"
            TrainingPhase.CLOSED -> "Учтена"
            TrainingPhase.REVIEW -> "Уточнение"
            TrainingPhase.CANCELLED -> "Отменена"
        }
        fun signed(value: Long) = if (value > 0) "+$value" else value.toString()
    }
}
