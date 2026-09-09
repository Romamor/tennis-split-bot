package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class SelfServiceBot(val api: TelegramApi, database: Database, val identity: TgUser,
    private val clock: Clock = Clock.systemUTC(), private val zone: String = "Europe/Moscow") {
    val service = SettlementService(database, clock)
    val state = InteractionStore(database, clock)
    val screens = Screens(service, state, requireNotNull(identity.username))
    private var lastCleanup = Long.MIN_VALUE
    private val checkedMembership = mutableMapOf<Pair<Long,Long>, Access>()
    init {
        val bound = database.read { c -> sqlQuery(c, "SELECT id FROM users WHERE is_bot=1") { it.getLong(1) }.singleOrNull() }
        require(bound == null || bound == identity.id) { "Эта база принадлежит другому боту" }
        service.remember(identity.account())
        state.interruptedSends()
        state.refreshLiveCards()
    }
    private fun TgUser.account() = Account(id, firstName, lastName, username, isBot)
    private fun remember(user: TgUser) { if (!user.isBot) service.remember(user.account()) }
    private fun access(group: Long, user: Long): Access {
        require(group < 0)
        checkedMembership[group to user]?.let { return it }
        val member = api.member(group, user)
        service.rememberMembership(group, user, member.present)
        checkAccounting(member.present, ErrorCode.FORBIDDEN, "Доступ только участникам этой Telegram-группы")
        return Access(group, user, member.admin).also { checkedMembership[group to user] = it }
    }
    fun handle(update: TgUpdate) {
        if (state.completed(update.id)) return
        checkedMembership.clear()
        val callback = update.callback
        val message = update.message ?: callback?.message
        val user = callback?.from ?: update.message?.from
        try {
            val memberUpdate = update.memberUpdate ?: update.botMemberUpdate
            if (memberUpdate != null) {
                if (memberUpdate.chat.id < 0) {
                    service.register(SettlementGroup(memberUpdate.chat.id, memberUpdate.chat.title ?: "Группа", zone))
                    remember(memberUpdate.from)
                    memberUpdate.member.user?.takeUnless { it.isBot }?.let {
                        remember(it); service.rememberMembership(memberUpdate.chat.id, it.id, memberUpdate.member.present)
                    }
                }
                state.complete(update.id); return
            }
            if (user == null || user.isBot || message == null) { state.complete(update.id); return }
            remember(user)
            if (message.chat.id < 0) {
                val title = message.chat.title ?: runCatching { service.group(message.chat.id).title }.getOrDefault("Группа")
                service.register(SettlementGroup(message.chat.id, title, zone))
                message.newMembers.filterNot { it.isBot }.forEach { remember(it); service.rememberMembership(message.chat.id, it.id, true) }
                message.leftMember?.takeUnless { it.isBot }?.let { remember(it); service.rememberMembership(message.chat.id, it.id, false) }
            }
            val saved = state.plan(update.id)
            val plan = saved ?: prepare(update, message, user)
                ?.let { positionAfterInput(it, callback == null && message.chat.type == "private") }
                ?.also { state.plan(update.id, it) }
            if (plan == null) { state.complete(update.id); return }
            val a = if (plan.screen.group < 0) access(plan.screen.group, plan.user) else null
            var effective = plan
            if (plan.command != null) {
                try {
                    service.execute(requireNotNull(a), "telegram:${update.id}", plan.command)
                    if (plan.command is SettlementCommand.RecordTransfer) effective = plan.copy(form = null)
                }
                catch (duplicate: DuplicateTransfer) {
                    effective = plan.copy(command = null, screen = ScreenAction("form", plan.screen.group),
                        form = requireNotNull(plan.form).copy(kind = "transfer_duplicate"))
                }
            }
            if (effective.screen.kind == "recover_card") {
                checkAccounting(service.isAdmin(requireNotNull(a)), ErrorCode.FORBIDDEN, "Доступно администратору группы")
                state.forgetDelivery("training:${a.groupId}:${effective.screen.id}")
                refreshCard(a.groupId, effective.screen.id)
                effective = effective.copy(screen = effective.screen.copy(kind = "training"))
            }
            if (effective.clearDraft) {
                val currentDraft=state.attendanceDraft(plan.user,plan.screen.group)
                if (currentDraft!=null && currentDraft!=effective.draft) {
                    state.complete(update.id)
                    return
                }
                if (currentDraft==effective.draft)
                    state.attendanceDraft(plan.user,plan.screen.group,null)
            }
            else effective.draft?.let { state.attendanceDraft(plan.user,plan.screen.group,it) }
            state.session(plan.user, plan.chat, plan.screen.group, effective.form)
            deliver(update.id, effective, a)
            state.complete(update.id)
        } catch (failure: TelegramFailure) {
            if (failure.kind in setOf(FailureKind.UNCERTAIN, FailureKind.RETRY_LATER)) throw failure
            answer(callback, "Не удалось выполнить действие. Проверь доступ бота к этой группе.")
            state.complete(update.id)
        } catch (failure: RuntimeException) {
            if (failure !is IllegalArgumentException && failure !is DateTimeException) throw failure
            val explanation = if (failure is DateTimeException) "Проверь формат даты и времени: ДД.ММ.ГГГГ и ЧЧ:ММ."
            else if (failure is AccountingException) when (failure.code) {
                ErrorCode.OUT_OF_RANGE -> "Слишком большое значение. Укажи меньшую сумму или время."
                else -> failure.message ?: "Не удалось выполнить действие"
            } else failure.message?.takeIf { it.any { ch -> ch in 'А'..'я' } } ?: "Проверь формат введённых данных."
            if (callback != null) answer(callback, explanation)
            else if (user != null && message?.chat?.type == "private") {
                val form = state.form(user.id, message.chat.id)
                val group = form?.group ?: state.selectedGroup(user.id, message.chat.id)
                val target = ScreenAction(if (form != null) "form" else if (group != null) "menu" else "groups", group ?: 0)
                val a = group?.let { runCatching { access(it, user.id) }.getOrNull() }
                val errorPlan = positionAfterInput(EventPlan(user.id, message.chat.id,
                    if (group != null && a == null) ScreenAction("groups", 0) else target,
                    form = form.takeIf { a != null }, notice = explanation), true)
                state.plan(update.id, errorPlan)
                deliver(update.id, errorPlan, a)
            }
            state.complete(update.id)
        }
    }
    private fun prepare(update: TgUpdate, message: TgMessage, user: TgUser): EventPlan? {
        val callback = update.callback
        val chat = message.chat.id
        val savedForm = state.form(user.id, chat)
        var action: ScreenAction
        if (callback != null) {
            val token = callback.data?.takeIf { it.startsWith("n:") }?.removePrefix("n:")
            val button = token?.let(state::button)
            if (button == null) {
                if (message.ephemeralId != null && message.from?.id == identity.id && message.receiver?.id == user.id)
                    closePanel(user.id, chat, message.ephemeralId)
                answer(callback, "Это старое меню. Нажми «Играл» на актуальной карточке или /start в личном чате.")
                return null
            }
            checkAccounting(button.owner == null || button.owner == user.id, ErrorCode.FORBIDDEN, "Эта панель открыта для другого участника")
            checkAccounting(chat > 0 || button.action.group == chat, ErrorCode.FORBIDDEN, "Эта кнопка относится к другой группе")
            if (chat < 0) checkAccounting(message.receiver == null || message.receiver.id == user.id, ErrorCode.FORBIDDEN, "Это чужая персональная панель")
            action = button.action
            if (chat > 0 && message.from?.id == identity.id) {
                val key = "personal:${user.id}:$chat"
                if (button.owner == user.id && button.scope == key && state.delivery(key)?.status == "UNKNOWN")
                    state.deliveryResult(key, "SENT", message.id)
            }
        } else {
            val text = message.text.orEmpty().trim()
            if (chat < 0) {
                if (text.substringBefore(' ').substringBefore('@') !in setOf("/start", "/menu")) return null
                access(chat, user.id)
                api.administrators(chat).mapNotNull { it.user }.filterNot { it.isBot }.forEach { remember(it); service.rememberMembership(chat, it.id, true) }
                return EventPlan(user.id, chat, ScreenAction("group_menu", chat))
            }
            if (text.startsWith("/start") || text == "/menu" || text == "Меню" || text == "/cancel") {
                val key = "personal:${user.id}:$chat"
                if (state.delivery(key)?.status == "UNKNOWN" && state.delivery(key)?.message == null) state.forgetDelivery(key)
                val link = text.substringAfter(' ', "").takeIf { it.startsWith("n_") }?.removePrefix("n_")?.let(state::button)
                action = if (link?.permanent == true) link.action else ScreenAction("groups", 0)
                if (link?.permanent == true) closePanel(user.id, link.action.group)
            } else if (message.usersShared != null && savedForm?.kind == "pick_account") {
                val a = access(savedForm.group, user.id)
                checkAccounting(service.isAdmin(a), ErrorCode.FORBIDDEN, "Добавлять аккаунты может администратор группы")
                require(message.usersShared.requestId == savedForm.request && message.usersShared.users.size == 1) { "Открой выбор аккаунта заново" }
                val shared = message.usersShared.users.single()
                service.remember(Account(shared.id, shared.firstName, shared.lastName, shared.username))
                // Admin explicitly selected a real Telegram account. A guest outside the chat is allowed in the training.
                val present = try { api.member(a.groupId, shared.id).present } catch (failure: TelegramFailure) {
                    if (failure.kind != FailureKind.REJECTED) throw failure
                    false
                }
                service.rememberMembership(a.groupId, shared.id, present)
                action = ScreenAction("player", a.groupId, savedForm.training, user = shared.id)
            } else if (savedForm != null) return textInput(update, user, chat, savedForm, text)
            else action = ScreenAction("groups", 0)
        }
        if (action.kind=="roster" && action.option=="admins") action=action.copy(kind="administrators",option="")
        val a = if (action.group < 0) access(action.group, user.id) else null
        val personalOnly = Screens.privateActions
        if (chat < 0 && action.kind in personalOnly) return EventPlan(user.id, chat, action.copy(kind = "private_link"), callback = callback?.id, ephemeral = message.ephemeralId)
        fun plan(screen: ScreenAction = action, command: SettlementCommand? = null, form: InputForm? = null) =
            EventPlan(user.id, chat, screen, command, form, callback?.id, message.ephemeralId)
        fun today() = LocalDate.now(clock.withZone(ZoneId.of(service.group(action.group).timeZone))).toString()
        fun trainingScreen() = action.copy(kind = "training", page = 0, user = 0, option = "")
        fun formPlan(form: InputForm) = plan(ScreenAction("form", form.group, form.training), form = form)
        return when (action.kind) {
            "new" -> {
                checkAccounting(service.isAdmin(requireNotNull(a)), ErrorCode.FORBIDDEN, "Создавать может администратор группы")
                formPlan(InputForm("title", action.group, date = today()))
            }
            "edit_details" -> {
                checkAccounting(service.isAdmin(requireNotNull(a)), ErrorCode.FORBIDDEN, "Изменять может администратор группы")
                val t = service.training(a, action.id)
                formPlan(InputForm("title", action.group, t.id, version = t.version, title = t.title, date = t.date, time = t.startTime))
            }
            "form_next", "form_restart", "save_training", "save_transfer", "transfer_date", "transfer_note" -> {
                val f = requireNotNull(savedForm) { "Открой форму заново" }
                checkAccounting(f.group == action.group, ErrorCode.FORBIDDEN, "Эта форма относится к другой группе")
                checkAccounting(action.option == state.formSignature(f), ErrorCode.STALE_VERSION, "Форма изменилась. Используй кнопки текущего сообщения")
                when (action.kind) {
                    "form_next" -> formPlan(advance(f))
                    "form_restart" -> formPlan(f.copy(kind = "title"))
                    "transfer_date", "transfer_note" -> formPlan(f.copy(kind = action.kind))
                    "save_training" -> {
                        require(f.kind == "ready") { "Сначала заполни тренировку" }
                        val id = f.training.ifEmpty { UUID.randomUUID().toString() }
                        val command = if (f.training.isEmpty()) SettlementCommand.CreateTraining(id, f.title, f.date, f.time)
                            else SettlementCommand.EditTraining(id, f.version, f.title, f.date, f.time)
                        plan(ScreenAction("training", f.group, id), command)
                    }
                    else -> {
                        require(f.kind in setOf("transfer_ready", "transfer_duplicate")) { "Сначала заполни перевод" }
                        val id = UUID.randomUUID().toString()
                        plan(ScreenAction("transfer", f.group, id), SettlementCommand.RecordTransfer(id,
                            if (f.direction == "out") user.id else f.user, if (f.direction == "out") f.user else user.id,
                            f.amount, f.date, f.note, allowSimilar = f.kind == "transfer_duplicate"), f)
                    }
                }
            }
            "player", "change", "reload_attendance" -> {
                val target=action.user.takeIf { it>0 } ?: user.id
                val auth=requireNotNull(a)
                checkAccounting(target==user.id || service.isAdmin(auth),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
                val requested=service.training(auth,action.id)
                val existing=state.attendanceDraft(user.id,action.group)
                if (requested.phase !in setOf(TrainingPhase.OPEN,TrainingPhase.REVIEW) && existing==null)
                    return plan(action.copy(kind="player",user=target))
                var draft=if (action.kind=="reload_attendance") newAttendanceDraft(auth,action.id,target,false)
                    else existing ?: newAttendanceDraft(auth,action.id,target,chat<0 && action.kind=="player" && action.user==0L)
                val same=draft.training==action.id && draft.user==target
                if (action.kind=="change" && same)
                    draft=draft.copy(value=service.previewAttendance(draft.value,AttendanceChange.valueOf(action.option),action.value))
                plan(ScreenAction("player",action.group,draft.training,user=draft.user)).copy(draft=draft,
                    notice=if (same) null else "Сначала заверши уже открытый ввод кнопкой «Всё правильно».")
            }
            "save_attendance", "discard_attendance" -> {
                val draft=state.attendanceDraft(user.id,action.group)
                val target=action.user.takeIf { it>0 } ?: user.id
                if (draft==null) return plan(if (chat<0) action.copy(kind="close_panel") else trainingScreen())
                checkAccounting(draft.training==action.id && draft.user==target && action.option==state.draftSignature(draft),
                    ErrorCode.STALE_VERSION,"Форма изменилась. Используй текущую кнопку «Всё правильно»")
                val command=if (action.kind=="save_attendance") SettlementCommand.SaveAttendance(draft.training,draft.user,draft.expected,draft.value) else null
                plan(if (chat<0) action.copy(kind="close_panel") else trainingScreen(),command).copy(clearDraft=true,draft=draft)
            }
            "finish" -> plan(trainingScreen(), SettlementCommand.FinishTraining(action.id, action.version))
            "reopen" -> plan(trainingScreen(), SettlementCommand.ReopenTraining(action.id, action.version))
            "cancel" -> plan(trainingScreen(), SettlementCommand.CancelTraining(action.id, action.version))
            "set_admin" -> {
                if (action.value==1L) {
                    val targetMember=api.member(action.group,action.user)
                    service.rememberMembership(action.group,action.user,targetMember.present)
                    checkAccounting(targetMember.present,ErrorCode.FORBIDDEN,"Назначить можно текущего участника группы")
                }
                plan(action.copy(kind="admin_person"),SettlementCommand.SetAdministrator(action.user,action.value==1L))
            }
            "ask_paid" -> formPlan(InputForm("paid", action.group, action.id, action.user))
            "transfer_amount", "suggested_transfer" -> formPlan(InputForm(if (action.kind == "suggested_transfer") "transfer_ready" else "transfer_amount",
                action.group, user = action.user, date = today(), direction = action.option, amount = action.value))
            "review_transfer", "confirm_transfer", "cancel_transfer" -> plan(action.copy(kind = "transfer"), SettlementCommand.ChangeTransfer(action.id, action.version, when (action.kind) {
                "review_transfer" -> TransferChange.REVIEW
                "confirm_transfer" -> TransferChange.CONFIRM
                else -> TransferChange.CANCEL
            }))
            "pick_account" -> {
                checkAccounting(service.isAdmin(requireNotNull(a)), ErrorCode.FORBIDDEN, "Добавлять может администратор группы")
                formPlan(InputForm("pick_account", action.group, action.id, request = (update.id % Int.MAX_VALUE).toInt()))
            }
            else -> plan()
        }
    }
    private fun newAttendanceDraft(a: Access, training: String, target: Long, join: Boolean): AttendanceDraft {
        checkAccounting(target==a.userId || service.isAdmin(a),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
        val t=service.training(a,training)
        checkAccounting(t.phase in setOf(TrainingPhase.OPEN,TrainingPhase.REVIEW),ErrorCode.INVALID_STATE,"Тренировка уже завершена. Попроси администратора возобновить её")
        val before=t.players.firstOrNull { it.userId==target }
        val value=before ?: Attendance(target,false)
        return AttendanceDraft(training,target,before,if (join) service.previewAttendance(value,AttendanceChange.JOIN) else value)
    }
    private fun advance(f: InputForm) = f.copy(kind = when (f.kind) { "title" -> "date"; "date" -> "time"; "time" -> "ready"; else -> error("Форма уже заполнена") })
    private fun textInput(update: TgUpdate, user: TgUser, chat: Long, f: InputForm, text: String): EventPlan {
        val auth=access(f.group, user.id)
        fun form(updated: InputForm) = EventPlan(user.id, chat, ScreenAction("form", f.group, f.training), form = updated)
        fun parsedDate() = runCatching { LocalDate.parse(text, DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrElse { LocalDate.parse(text) }.toString()
        return when (f.kind) {
            "title" -> { require(text.length in 1..100) { "Название: от 1 до 100 символов" }; form(advance(f.copy(title = text))) }
            "date" -> form(advance(f.copy(date = parsedDate())))
            "time" -> form(advance(f.copy(time = LocalTime.parse(text).format(DateTimeFormatter.ofPattern("HH:mm")))))
            "paid" -> {
                val amount = if (text == "0") 0 else parseAmount(text)
                val draft=state.attendanceDraft(user.id,f.group) ?: newAttendanceDraft(auth,f.training,f.user,false)
                checkAccounting(draft.training==f.training && draft.user==f.user,ErrorCode.INVALID_STATE,"Сначала заверши уже открытый ввод")
                checkAccounting(f.user==user.id || service.isAdmin(auth),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
                EventPlan(user.id, chat, ScreenAction("player", f.group, f.training, user = f.user),
                    draft=draft.copy(value=service.previewAttendance(draft.value,AttendanceChange.SET_PAID,amount)))
            }
            "transfer_amount" -> form(f.copy(kind = "transfer_ready", amount = parseAmount(text)))
            "transfer_date" -> form(f.copy(kind = "transfer_ready", date = parsedDate()))
            "transfer_note" -> { require(text.length <= 300) { "Комментарий не длиннее 300 символов" }; form(f.copy(kind = "transfer_ready", note = text)) }
            else -> form(f)
        }
    }
    private fun answer(callback: TgCallback?, text: String) { callback?.let { runCatching { api.answer(it.id, text.take(180), true) } } }
    /** Freeze the old message identity with the input event, so retries cannot create another reply. */
    private fun positionAfterInput(plan: EventPlan, incomingPrivateMessage: Boolean): EventPlan {
        if (!incomingPrivateMessage) return plan
        val key = "personal:${plan.user}:${plan.chat}"
        val old = state.delivery(key)
        // A new user message permits a fresh reply even if the previous reply never arrived.
        if (old?.status == "UNKNOWN" && old.message == null) state.forgetDelivery(key)
        return plan.copy(newPrivateMessage = true, previousPrivateMessage = old?.message)
    }
    private fun deliver(event: Long, plan: EventPlan, a: Access?) {
        if (plan.screen.kind == "group_menu") {
            val token = state.button(ScreenAction("menu", plan.screen.group), null, "group-link:${plan.screen.group}", true)
            val out = Screens.Output("${Screens.clean(service.group(plan.screen.group).title, 80)}\n\nТренировки и расчёты этой группы.", TgKeyboard(listOf(listOf(TgButton("Открыть меню группы", url = "https://t.me/${identity.username}?start=n_$token")))), emptySet())
            sendOrdinary("group-menu:${plan.screen.group}", plan.screen.group, plan.chat, null, out, "group-menu:${plan.screen.group}")
            return
        }
        if (plan.chat < 0 && plan.screen.kind in setOf("private_link", "close_panel")) {
            closePanel(plan.user, plan.chat, plan.ephemeral)
            val text = if (plan.screen.kind == "private_link") "Открой актуальные кнопки через «Играл» на общей карточке." else null
            plan.callback?.let { runCatching { api.answer(it, text, text != null) } }
            return
        }
        val scope = "personal:${plan.user}:${plan.chat}"
        val administrators=if (plan.screen.kind in setOf("administrators","admin_candidates","admin_person")) {
            checkAccounting(a?.telegramAdmin==true,ErrorCode.FORBIDDEN,"Управлять назначениями могут администраторы Telegram-группы")
            api.administrators(plan.screen.group).mapNotNull { it.user }.filterNot { it.isBot }.onEach {
                remember(it);service.rememberMembership(plan.screen.group,it.id,true)
            }.map { it.id }.toSet()+plan.user
        } else emptySet()
        val out = screens.render(plan.screen, a, scope, plan.user, plan.form, plan.notice, inGroup = plan.chat < 0, telegramAdmins=administrators)
        if (plan.chat < 0) {
            // Only training data already visible on the shared card is rendered inside the group.
            val oldTokens = state.activeTokens(scope)
            state.protect(out.tokens)
            try {
                val current = state.currentEphemeral(plan.user, plan.chat) ?: plan.ephemeral
                var updated = false
                if (current != null) {
                    try {
                        api.editEphemeral(plan.chat, plan.user, current, out.text, out.keyboard)
                        state.rememberEphemeral(plan.user, plan.chat, current)
                        updated = true
                    } catch (failure: TelegramFailure) {
                        if (failure.kind != FailureKind.MESSAGE_MISSING && !(failure.kind == FailureKind.REJECTED && failure.code == 400)) throw failure
                    }
                }
                if (!updated) {
                    val sent = api.ephemeral(plan.chat, plan.user, requireNotNull(plan.callback), out.text, out.keyboard)
                    state.rememberEphemeral(plan.user, plan.chat, sent.ephemeralId)
                    if (current != null && current != sent.ephemeralId) deletePanel(plan.user, plan.chat, current)
                }
                if (plan.ephemeral != null && plan.ephemeral != state.currentEphemeral(plan.user, plan.chat))
                    deletePanel(plan.user, plan.chat, plan.ephemeral)
                state.replace(scope, out.tokens)
            } catch (failure: TelegramFailure) {
                if (failure.code in setOf(401, 409)) throw failure
                System.err.println("Telegram: персональная панель не доставлена; ${failure.kind}, код=${failure.code ?: "нет"}")
                if (failure.kind != FailureKind.UNCERTAIN) state.replace(scope, oldTokens)
                val notice = (if (plan.command != null) "Изменение сохранено. " else "") +
                    "Панель не открылась. Нажми «Открыть у бота» на общей карточке."
                plan.callback?.let { runCatching { api.answer(it, notice, true) } }
                if (failure.kind == FailureKind.RETRY_LATER) throw failure
                return
            }
        } else {
            val key = "personal:${plan.user}:${plan.chat}"
            val old = state.delivery(key)
            if (plan.newPrivateMessage && old?.message == plan.previousPrivateMessage && old?.status !in setOf("SENDING", "UNKNOWN"))
                state.forgetDelivery(key)
            sendOrdinary(key, plan.screen.group, plan.chat, plan.user, out, scope)
            if (plan.form?.kind == "pick_account") {
                val pickerKey = "picker:$event"
                if (state.delivery(pickerKey) == null) {
                    state.sending(pickerKey, plan.screen.group, plan.chat, plan.user)
                    try {
                        val msg = api.requestUsers(plan.chat, "Выбери аккаунт Telegram для этой тренировки.", plan.form.request)
                        state.deliveryResult(pickerKey, "SENT", msg.id)
                    } catch (failure: TelegramFailure) { state.deliveryResult(pickerKey, if (failure.kind == FailureKind.UNCERTAIN) "UNKNOWN" else "FAILED"); throw failure }
                }
            }
        }
        plan.callback?.let { runCatching { api.answer(it) } }
    }
    private fun deletePanel(user: Long, chat: Long, id: Long) {
        try { api.deleteEphemeral(chat, user, id) }
        catch (failure: TelegramFailure) {
            if (failure.code in setOf(401,409)) throw failure
            System.err.println("Telegram: закрытие персональной панели; ${failure.kind}, код=${failure.code ?: "нет"}")
        }
    }
    private fun closePanel(user: Long, chat: Long, id: Long? = null) {
        val current = state.currentEphemeral(user, chat)
        val target = id ?: current ?: return
        deletePanel(user, chat, target)
        if (current == null || current == target) {
            state.rememberEphemeral(user, chat, null)
            state.replace("personal:$user:$chat", emptySet())
        }
    }
    private fun sendOrdinary(key: String, group: Long, chat: Long, user: Long?, out: Screens.Output, scope: String): Boolean {
        val old = state.delivery(key)
        if (old?.status == "UNKNOWN" && old.message == null) return false
        val oldTokens = state.activeTokens(scope)
        state.protect(out.tokens)
        state.sending(key, group, chat, user)
        try {
            if (old?.message != null) {
                try { api.edit(chat, old.message, out.text, out.keyboard) }
                catch (failure: TelegramFailure) {
                    if (failure.kind != FailureKind.MESSAGE_MISSING) throw failure
                    state.forgetDelivery(key)
                    return sendOrdinary(key, group, chat, user, out, scope)
                }
                state.deliveryResult(key, "SENT", old.message)
            } else {
                val msg = api.send(chat, out.text, out.keyboard)
                state.deliveryResult(key, "SENT", msg.id)
            }
            state.replace(scope, out.tokens)
            return true
        } catch (failure: TelegramFailure) {
            state.deliveryResult(key, when (failure.kind) { FailureKind.UNCERTAIN -> "UNKNOWN"; FailureKind.RETRY_LATER -> "RETRY"; else -> "FAILED" })
            if (failure.kind != FailureKind.UNCERTAIN) state.replace(scope, oldTokens)
            if (failure.kind == FailureKind.UNCERTAIN && old?.message == null) return false
            throw failure
        }
    }
    fun maintain() {
        val now = clock.instant().epochSecond
        if (lastCleanup == Long.MIN_VALUE || now - lastCleanup >= 30) { state.cleanup(); lastCleanup = now }
        state.pendingCards().forEach { (group, training, through) ->
            if (state.delivery("training:$group:$training")?.status != "FAILED") {
                try { if (refreshCard(group, training)) state.cardDelivered(group, training, through) }
                catch (failure: TelegramFailure) {
                    if (failure.kind != FailureKind.REJECTED || failure.code in setOf(401,409)) throw failure
                }
            }
        }
    }
    private fun refreshCard(group: Long, training: String): Boolean {
        val author = service.database.read { c -> sqlQuery(c, "SELECT created_by FROM trainings WHERE group_id=? AND id=?", group, training) { it.getLong(1) }.single() }
        // Public information for its original chat. No private context or privileges are used for background delivery.
        val scope = "training:$group:$training"
        val out = screens.render(ScreenAction("public", group, training), Access(group, author), scope, null)
        return sendOrdinary(scope, group, group, null, out, scope)
    }
}
