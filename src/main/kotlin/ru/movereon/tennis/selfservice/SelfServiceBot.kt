package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.time.Clock
import java.time.DateTimeException
import java.util.UUID

class SelfServiceBot(val api: TelegramApi, database: Database, val identity: TgUser,
    private val clock: Clock = Clock.systemUTC(), private val zone: String = "Europe/Moscow") {
    val service = SettlementService(database, clock)
    val state = InteractionStore(database, clock)
    val polls=TrainingPolls(service,clock)
    private val pollWorkflow=PollWorkflow(polls,state,api)
    val screens = Screens(service, state, requireNotNull(identity.username))
    private val paymentInput=PaymentInput(service,state)
    private val trainingInput=TrainingInput(service,state,polls,clock,zone,::publicationChoices,::requirePublication)
    private val inputRecovery=InputRecovery(service,::access)
    private val delivery=MessageDelivery(api,state,clock)
    private val paymentNotifications=PaymentNotifications(service,state,clock) { key,group,user,out ->
        delivery.send(key,group,user,user,out,key,recreateMissing=state.delivery(key)?.message==null)
    }
    private var lastCleanup = Long.MIN_VALUE
    private val cardRetries=object:LinkedHashMap<Pair<Long,String>,Long>() {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Long,String>,Long>?)=size>256
    }
    private val navigationMembership=NavigationMembershipCache(clock)
    private val checkedMembership = mutableMapOf<Pair<Long,Long>, Access>()
    init {
        val bound = database.read { c -> sqlQuery(c, "SELECT id FROM users WHERE is_bot=1") { it.getLong(1) }.singleOrNull() }
        require(bound == null || bound == identity.id) { "Эта база принадлежит другому боту" }
        service.remember(identity.account())
        state.interruptedSends()
        polls.interrupted()
        service.synchronizeOpenTrainingRules()
        state.refreshLiveCards()
        state.recoverPrivatePollViews()
    }
    private fun TgUser.account() = Account(id, firstName, lastName, username, isBot)
    private fun remember(user: TgUser) { if (!user.isBot) service.remember(user.account()) }
    private fun access(group:Long,user:Long)=checkedAccess(group,user,allowFormer=false)
    private fun screenAccess(action:ScreenAction,user:Long,chat:Long,mutating:Boolean=false)=
        checkedAccess(action.group,user,!mutating && chat>0 && action.kind in FormerMemberScreens.kinds)
    private fun checkedAccess(group: Long, user: Long, allowFormer:Boolean): Access {
        require(group < 0)
        checkedMembership[group to user]?.let { return it }
        val member = try { api.member(group, user) } catch(f:TelegramFailure) {
            if(f.kind!=FailureKind.REJECTED || f.code !in setOf(400,403)) throw f
            navigationMembership.invalidate(group)
            throw AccountingException(ErrorCode.FORBIDDEN,"Группа недоступна. Выбери другую группу или вернись в меню.")
        }
        navigationMembership.remember(group,user,member)
        service.observeMembership(group, user, member.present)
        if(!member.present && allowFormer) {
            val a=Access(group,user)
            service.requireKnownGroupMember(a)
            return a
        }
        checkAccounting(member.present, ErrorCode.FORBIDDEN, "Доступ только участникам этой Telegram-группы")
        return Access(group, user, member.admin).also { checkedMembership[group to user] = it }
    }
    private fun groupOptions(user:Long,forPublication:Boolean=false,discover:Boolean=false,includeFormer:Boolean=false):List<GroupOption> {
        val known=service.knownGroups(user,includeFormer)
        val groups=if(discover || known.isEmpty()) service.knownGroups() else known
        return groups.mapNotNull { group ->
        try {
            val member=navigationMembership.get(group.id,user) { api.member(group.id,user) }
            service.observeMembership(group.id,user,member.present)
            if(!member.present) {
                if(includeFormer && group in known) GroupOption(group,false,false,false,present=false) else null
            } else {
                val auth=Access(group.id,user,member.admin)
                val canPublish=(forPublication || polls.enabled(group.id)) && navigationMembership.get(group.id,identity.id) { api.member(group.id,identity.id) }.let { it.present && it.admin }
                GroupOption(group,service.isAdmin(auth),member.admin,canPublish)
            }
        } catch(f:TelegramFailure) {
            if(f.kind==FailureKind.REJECTED && f.code in setOf(400,403)) null else throw f
        }
    }
    }
    private fun publicationChoices(user:Long,form:InputForm)=GroupSelection.publication(
        groupOptions(user,forPublication=true,discover=true),form.pollId.isNotEmpty(),polls::enabled)

    private fun groupDestination(section:String,choice:GroupOption,user:Long):ScreenAction {
        val auth=checkedAccess(choice.group.id,user,GroupSelection.allowsFormer(section))
        val present=service.isPresent(auth)
        return GroupSelection.destination(section,choice.copy(admin=present && service.isAdmin(auth),superAdmin=present && auth.telegramAdmin,present=present))
    }

    /** Resolve before sending a message: the common single-group path has no intermediate picker. */
    private fun skipSingleGroup(plan:EventPlan):EventPlan {
        if(plan.chat<0 || plan.command!=null) return plan
        val screen=plan.screen
        if(screen.kind in setOf("groups","groups_back")) {
            val choices=GroupSelection.choices(groupOptions(plan.user,discover=true,includeFormer=GroupSelection.allowsFormer(screen.option)),screen.option) { polls.hasActive(it.group.id,plan.user,it.admin) }
            val next=when {
                screen.option=="polls" && choices.isEmpty() -> GroupSelection.parent(screen.option)
                screen.kind=="groups_back" && choices.size<=1 -> GroupSelection.parent(screen.option)
                screen.kind=="groups_back" -> screen.copy(kind="groups")
                choices.size==1 -> groupDestination(screen.option,choices.single(),plan.user)
                else -> screen
            }
            return plan.copy(screen=next)
        }
        val form=plan.form
        if(screen.kind=="form" && form?.kind=="group" && form.group==0L && form.training.isEmpty()) {
            val only=publicationChoices(plan.user,form).singleOrNull() ?: return plan
            requirePublication(only.group.id,plan.user)
            return plan.copy(form=form.copy(kind="ready",publishGroup=only.group.id))
        }
        return plan
    }

    private fun requirePublication(group:Long,user:Long) {
        access(group,user)
        checkAccounting(api.member(group,identity.id).let { it.present && it.admin },ErrorCode.FORBIDDEN,
            "Для публикации бот должен быть администратором этой группы. Выбери другую группу или проверь права бота.")
    }
    fun handle(update: TgUpdate) {
        if (state.completed(update.id)) return
        checkedMembership.clear()
        val callback = update.callback
        val message = update.message ?: callback?.message
        val user = callback?.from ?: update.message?.from
        try {
            if(update.pollAnswer!=null) {
                val vote=update.pollAnswer
                val p=polls.byTelegram(vote.pollId)
                val voter=vote.user
                if(p==null || voter==null || voter.isBot || p.status !in setOf("OPEN","CLOSING")) { state.complete(update.id);return }
                val option=vote.optionIds.singleOrNull()
                if(option in 0..2) {
                    remember(voter)
                    try { access(p.group,voter.id) } catch(f:AccountingException) {
                        if(f.code!=ErrorCode.FORBIDDEN) throw f
                        polls.vote(update.id,p,voter.id,null);return
                    }
                }
                polls.vote(update.id,p,voter.id,option);return
            }
            val memberUpdate = update.memberUpdate ?: update.botMemberUpdate
            if (memberUpdate != null) {
                if (memberUpdate.chat.id < 0) {
                    navigationMembership.invalidate(memberUpdate.chat.id)
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
                if(message.newMembers.isNotEmpty() || message.leftMember!=null) navigationMembership.invalidate(message.chat.id)
                val title = message.chat.title ?: runCatching { service.group(message.chat.id).title }.getOrDefault("Группа")
                service.register(SettlementGroup(message.chat.id, title, zone))
                message.newMembers.filterNot { it.isBot }.forEach { remember(it); service.rememberMembership(message.chat.id, it.id, true) }
                message.leftMember?.takeUnless { it.isBot }?.let { remember(it); service.rememberMembership(message.chat.id, it.id, false) }
            }
            val saved = state.plan(update.id)?.let { previous ->
                if(FinanceScreens.retiredCommand(previous.command) || previous.screen.kind in FinanceScreens.retiredActions || FinanceScreens.retiredForm(previous.form)) previous.copy(command=null,form=null,screen=ScreenAction("finance",previous.screen.group),notice="Меню переводов обновилось. Используй «Перевести» или «Принять перевод».")
                else previous
            }?.let(inputRecovery::resolve)
            val plan = saved ?: prepare(update, message, user)
                ?.let(::skipSingleGroup)
                ?.let(inputRecovery::resolve)
                ?.let { positionAfterInput(it, callback == null && message.chat.type == "private") }
                ?.also { state.plan(update.id, it) }
            if (plan == null) { state.complete(update.id); return }
            val closingOwnPanel=plan.chat<0 && plan.screen.kind=="close_panel" && plan.command==null
            val a = if (plan.screen.group < 0 && !closingOwnPanel) screenAccess(plan.screen,plan.user,plan.chat,mutating=plan.command!=null) else null
            var effective = plan
            plan.defaultUpdate?.let { service.updateTrainingDefaults(plan.user,it) }
            if (plan.command != null) {
                if(plan.command is SettlementCommand.CreateTraining) requirePublication(plan.screen.group,plan.user)
                try {
                    service.execute(requireNotNull(a), "telegram:${update.id}", plan.command)
                    if (plan.command is SettlementCommand.SendOtherPayment || plan.command is SettlementCommand.RecordAdminPayment) effective = plan.copy(form = null)
                }
                catch (duplicate: DuplicateTransfer) {
                    effective = plan.copy(command = null, screen = ScreenAction("form", plan.screen.group),
                        form = requireNotNull(plan.form).copy(kind = "payment_duplicate",similar=duplicate.ids))
                }
            }
            effective=pollWorkflow.execute(effective,a,::requirePublication)
            if(effective.screen.kind=="group_rule_save") {
                service.setGroupTrainingRule(requireNotNull(a),effective.screen.option,effective.screen.value==1L,"telegram:${update.id}")
                effective=effective.copy(screen=ScreenAction("poll_settings",a.groupId))
            }
            if(effective.screen.kind=="poll_setting_save") {
                polls.setEnabled(requireNotNull(a),effective.screen.value==1L)
                effective=effective.copy(screen=ScreenAction("poll_settings",a.groupId))
            }
            if(effective.screen.kind=="edit_redirect") {
                // Recheck on replay too: a saved event never preserves administrative rights.
                val auth=requireNotNull(a)
                checkAccounting(service.canEdit(auth,service.training(auth,effective.screen.id)),ErrorCode.FORBIDDEN,
                    "Редактировать тренировку может её создатель или администратор этой группы")
                val target=ScreenAction("edit_training",auth.groupId,effective.screen.id)
                val token=state.button(target,null,"edit-link:${auth.groupId}:${target.id}",permanent=true)
                api.openPrivate(requireNotNull(effective.callback),"https://t.me/${identity.username}?start=n_$token")
                state.complete(update.id)
                return
            }
            if(effective.screen.kind=="retry_pin") {
                checkAccounting(service.isAdmin(requireNotNull(a)),ErrorCode.FORBIDDEN,"Доступно администратору группы")
                val training=service.training(a,effective.screen.id)
                state.pinStatus("training:${a.groupId}:${effective.screen.id}",if(training.phase in setOf(TrainingPhase.CLOSED,TrainingPhase.CANCELLED)) "UNPIN_PENDING" else "PENDING")
                state.reconcilePins();unpinCards();pinCards()
                effective=effective.copy(screen=effective.screen.back ?: effective.screen.copy(kind="training"))
            }
            if(effective.screen.kind=="public_page") {
                requireNotNull(a)
                service.training(a,effective.screen.id)
                // Previously sent page buttons now refresh the complete card.
                refreshCard(a.groupId,effective.screen.id)
                effective.callback?.let { api.answer(it) }
                state.complete(update.id);return
            }
            if (effective.screen.kind == "recover_card") {
                checkAccounting(service.isAdmin(requireNotNull(a)), ErrorCode.FORBIDDEN, "Доступно администратору группы")
                state.forgetDelivery("training:${a.groupId}:${effective.screen.id}")
                refreshCard(a.groupId, effective.screen.id,allowCreate=true)
                effective = effective.copy(screen = effective.screen.copy(kind = "training"))
            }
            if (effective.clearDraft) {
                val draftGroup=effective.clearDraftGroup ?: plan.screen.group
                if(effective.nextDraft!=null && plan.screen.group!=draftGroup) {
                    val replacement=state.attendanceDraft(plan.user,plan.screen.group)
                    if(replacement!=null && replacement!=effective.nextDraft) {
                        state.complete(update.id)
                        return
                    }
                }
                val currentDraft=state.attendanceDraft(plan.user,draftGroup)
                if (currentDraft!=null && currentDraft!=effective.draft) {
                    state.complete(update.id)
                    return
                }
                if (currentDraft==effective.draft)
                    state.attendanceDraft(plan.user,draftGroup,null)
                effective.nextDraft?.let { state.attendanceDraft(plan.user,plan.screen.group,it) }
            }
            else effective.draft?.let { state.attendanceDraft(plan.user,plan.screen.group,it) }
            state.session(plan.user, plan.chat, plan.screen.group, effective.form)
            deliver(update.id, effective, a)
            effective.inputMessage?.let { state.retirePrivateMenu(effective.user,effective.chat,it,input=true) }
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
                val group = form?.group?.takeIf { it<0 } ?: state.selectedGroup(user.id, message.chat.id)
                val target = ScreenAction(if (form?.kind=="add_players") "add_players" else if (form != null) "form" else "menu", group ?: 0,form?.training.orEmpty(),page=form?.page ?: 0)
                val a = group?.let { runCatching { access(it, user.id) }.getOrNull() }
                val safeForm=form.takeIf { it?.group==0L || a!=null && (it?.kind !in setOf("add_players","pick_players") || canEdit(a,it!!.training)) }
                val errorPlan = positionAfterInput(inputRecovery.resolve(EventPlan(user.id, message.chat.id,
                    if (group != null && a == null) ScreenAction("groups", 0)
                    else if(form!=null && safeForm==null) ScreenAction("menu",group ?: 0) else target,
                    form = safeForm, notice = explanation)), true)
                state.plan(update.id, errorPlan)
                state.session(user.id,message.chat.id,errorPlan.screen.group,errorPlan.form)
                deliver(update.id, errorPlan, a)
            }
            state.complete(update.id)
        }
    }
    private fun prepare(update: TgUpdate, message: TgMessage, user: TgUser, destination:ScreenAction?=null,
        discardForm:Boolean=false,discardDraftGroup:Long?=null): EventPlan? {
        val callback = update.callback
        val chat = message.chat.id
        val savedForm = if(discardForm) null else state.form(user.id, chat)
        var action: ScreenAction
        var openedNotice=false
        if(destination!=null) action=destination
        else if (callback != null) {
            val token = callback.data?.takeIf { it.startsWith("n:") }?.removePrefix("n:")
            val button = token?.let(state::button)
            val incomingNotice=chat==user.id && button?.owner==user.id && button.scope.startsWith("payment-notice:") && state.delivery(button.scope)?.status!="BLOCKED"
            if(chat>0 && !incomingNotice && message.from?.id==identity.id && message.id>0 && state.delivery("personal:${user.id}:$chat")?.message!=message.id) {
                state.retirePrivateMenu(user.id,chat,message.id)
                delivery.retirePrivateMenus(user.id)
            }
            if (button == null) {
                if (chat<0 && message.ephemeralId != null && message.from?.id == identity.id &&
                    (message.receiver==null || message.receiver.id==user.id))
                    closePanel(user.id, chat, message.ephemeralId,required=true)
                answer(callback, "Это старое меню. Нажми «Открыть» на актуальной карточке или /start в личном чате.")
                return null
            }
            checkAccounting(button.owner == null || button.owner == user.id, ErrorCode.FORBIDDEN, "Эта панель открыта для другого участника")
            checkAccounting(chat > 0 || button.action.group == chat, ErrorCode.FORBIDDEN, "Эта кнопка относится к другой группе")
            if (chat < 0) checkAccounting(message.receiver == null || message.receiver.id == user.id, ErrorCode.FORBIDDEN, "Это чужая персональная панель")
            if (chat < 0 && message.ephemeralId != null) {
                val current = state.currentEphemeral(user.id, chat)
                if (current != null && current != message.ephemeralId) {
                    closePanel(user.id, chat, message.ephemeralId, required=true)
                    answer(callback, "Это прежняя панель. Используй последнюю открытую панель.")
                    return null
                }
            }
            action = button.action
            if(incomingNotice) {
                state.observePaymentNotice(button.scope,user.id,message.id)
                openedNotice=(state.delivery("personal:${user.id}:$chat")?.message ?: 0L)<(state.lastPaymentNoticeMessage(user.id) ?: message.id)
            }
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
                action = if (link?.permanent == true) link.action else ScreenAction("menu", 0)
                if (link?.permanent == true) closePanel(user.id, link.action.group)
            } else if (message.usersShared != null && savedForm?.kind in setOf("pick_account","pick_players","pick_add_player")) {
                requireNotNull(savedForm)
                val a = access(savedForm.group, user.id)
                checkAccounting(canEdit(a,savedForm.training), ErrorCode.FORBIDDEN, "Добавлять аккаунты может создатель тренировки или администратор группы")
                require(message.usersShared.requestId == savedForm.request && message.usersShared.users.size == 1) { "Открой выбор аккаунта заново" }
                val shared = message.usersShared.users.single()
                service.remember(Account(shared.id, shared.firstName, shared.lastName, shared.username))
                // Admin explicitly selected a real Telegram account. A guest outside the chat is allowed in the training.
                val present = try { api.member(a.groupId, shared.id).present } catch (failure: TelegramFailure) {
                    if (failure.kind != FailureKind.REJECTED) throw failure
                    false
                }
                service.rememberMembership(a.groupId, shared.id, present)
                if(savedForm.kind=="pick_add_player") {
                    val t=service.training(a,savedForm.training)
                    service.requireOpen(t)
                    return EventPlan(user.id,chat,requireNotNull(savedForm.origin),SettlementCommand.AddPlayers(t.id,t.version,listOf(shared.id)))
                }
                if(savedForm.kind=="pick_players") {
                    val t=service.training(a,savedForm.training)
                    checkAccounting(t.phase == TrainingPhase.OPEN,ErrorCode.INVALID_STATE,"Тренировка уже учтена")
                    val selected=(savedForm.selectedUsers+shared.id).distinct().filter { id -> t.players.none { it.userId==id && it.playing } }
                    return EventPlan(user.id,chat,ScreenAction("add_players",a.groupId,t.id,page=savedForm.page),
                        form=savedForm.copy(kind="add_players",selectedUsers=selected,order=(savedForm.order.orEmpty()+shared.id).distinct()))
                }
                action = ScreenAction("player", a.groupId, savedForm.training, user = shared.id)
            } else if(savedForm?.kind=="poll_photo" && message.photo.isNotEmpty()) {
                val photo=message.photo.maxBy { it.width.toLong()*it.height }
                require(photo.fileId.isNotBlank()) { "Некорректная фотография опроса" }
                return EventPlan(user.id,chat,ScreenAction("form",0),form=savedForm.copy(kind="ready",pollPhotoId=photo.fileId))
            } else if(FinanceScreens.retiredForm(savedForm)) {
                return EventPlan(user.id,chat,ScreenAction("finance",requireNotNull(savedForm).group),notice="Меню переводов обновилось. Используй «Перевести» или «Принять перевод».")
            } else if (savedForm != null) return textInput(update, user, chat, savedForm, text)
            else action = ScreenAction("groups", 0)
        }
        if(action.kind=="trainings" && action.option=="mine") action=ScreenAction("my_trainings",0)
        if(action.kind in setOf("menu","settings","training_settings","default_title","default_time","new","new_poll","my_trainings"))
            action=action.copy(group=0,id="")
        if(action.kind=="select_group") {
            val choice=groupOptions(user.id,includeFormer=GroupSelection.allowsFormer(action.option)).firstOrNull { it.group.id==action.value }
                ?: throw AccountingException(ErrorCode.FORBIDDEN,"Группа больше недоступна")
            action=groupDestination(action.option,choice,user.id)
        }
        if(FinanceScreens.retiredForm(savedForm) && action.kind in setOf("form","form_next","form_restart","form_date_adjust","form_time_adjust"))
            action=ScreenAction("finance",requireNotNull(savedForm).group)
        if(action.kind in FinanceScreens.retiredActions) action=ScreenAction("finance",action.group)
        if (action.kind=="roster" && action.option=="admins") action=action.copy(kind="administrators",option="")
        if(chat>0 && action.kind=="form") action=savedForm?.let { ScreenAction("form",it.group,it.training,back=it.origin) } ?: ScreenAction("menu",0)
        val a = if (action.group < 0 && action.kind!="close_panel") screenAccess(action,user.id,chat) else null
        if(callback!=null && action.kind=="finance_receive" && service.pendingPaymentCount(requireNotNull(a))==0) {
            answer(callback,"Нет переводов для подтверждения")
            return null
        }
        if(action.kind in setOf("poll_settings","poll_setting_save","group_rule_save"))
            checkAccounting(service.isAdmin(requireNotNull(a)),ErrorCode.FORBIDDEN,"Настройка доступна администратору этой группы")
        if(action.kind in setOf("poll_close_confirm","poll_close","poll_retry","poll_detail","poll_discard_confirm","poll_discard")) {
            val p=polls.get(requireNotNull(a).groupId,action.id)
            polls.requireManager(a,p)
            if(chat<0 && p.telegramId==null && message.from?.id==identity.id && message.poll!=null && action.kind=="poll_close_confirm") {
                polls.attach(p,message.poll.id,message.id)
                pollWorkflow.recordDelivery(polls.get(a.groupId,p.id))
            } else if(chat<0) checkAccounting(message.poll?.id==p.telegramId && message.id==p.message || message.ephemeralId!=null,
                ErrorCode.FORBIDDEN,"Эта кнопка относится к другому сообщению опроса")
        }
        if(action.kind=="edit_training") {
            val auth=requireNotNull(a)
            checkAccounting(service.canEdit(auth,service.training(auth,action.id)),ErrorCode.FORBIDDEN,"Редактировать тренировку может её создатель или администратор этой группы")
            if(chat<0) return EventPlan(user.id,chat,action.copy(kind="edit_redirect"),callback=callback?.id)
            action=action.copy(kind="training")
        }
        if(action.kind in setOf("training","training_status","set_training_status","add_player_list","exclude_player_list","manage_players","add_player","remove_player","pick_add_player"))
            checkAccounting(canEdit(requireNotNull(a),action.id),ErrorCode.FORBIDDEN,"Редактировать тренировку может её создатель или администратор этой группы")
        if(action.kind in setOf("add_player_list","exclude_player_list","manage_players","add_player","remove_player","pick_add_player","edit_details"))
            service.requireOpen(service.training(requireNotNull(a),action.id))
        if(action.kind in setOf("participation","participation_time","participation_payment","participation_change","player","change","reload_attendance","ask_paid","save_attendance"))
            service.requireOpen(service.training(requireNotNull(a),action.id))
        val personalOnly = Screens.privateActions
        if (chat < 0 && action.kind in personalOnly) return EventPlan(user.id, chat, action.copy(kind = "private_link"), callback = callback?.id, ephemeral = message.ephemeralId)
        fun plan(screen: ScreenAction = action, command: SettlementCommand? = null, form: InputForm? = null) =
            EventPlan(user.id, chat, screen, command, form, callback?.id, message.ephemeralId,
                newPrivateMessage=openedNotice,previousPrivateMessage=if(openedNotice) state.delivery("personal:${user.id}:$chat")?.message else null)
        fun trainingScreen() = action.back?.takeIf { it.kind=="training" && it.id==action.id } ?: action.copy(kind = "training", page = 0, user = 0, option = "")
        fun formPlan(form: InputForm) = plan(ScreenAction("form", form.group, form.training,back=form.origin ?: action.back),
            form = form.copy(origin=form.origin ?: action.back))
        val inputGroup=savedForm?.group ?: (if(discardForm) null else state.selectedGroup(user.id,chat)) ?: action.group
        val exiting=action.kind in InputNavigation.screens
        val checksInput=exiting || action.kind in InputNavigation.entries || action.kind in setOf("exit_discard","exit_continue")
        val activeDraft=if(checksInput && inputGroup!=discardDraftGroup) state.attendanceDraft(user.id,inputGroup) else null
        val replacing=action.kind in InputNavigation.entries && !InputNavigation.continues(action,savedForm,activeDraft,inputGroup)
        val signature=if(checksInput)
            state.formSignature(InputForm("exit_guard",inputGroup,attendance=activeDraft,baseline=savedForm?.let(state::formSignature))) else ""
        val unsaved=(!FinanceScreens.retiredForm(savedForm) && savedForm.hasChanges()) || draftDirty(activeDraft)
        if((exiting || replacing) && unsaved && inputGroup<0) {
            try { access(inputGroup,user.id) }
            catch(e:AccountingException) {
                if(e.code!=ErrorCode.FORBIDDEN || service.isPresent(Access(inputGroup,user.id))) throw e
                val next=prepare(update,message,user,action,discardForm=true,discardDraftGroup=inputGroup) ?: return null
                return next.copy(notice="Ввод закрыт: ты больше не участник группы. Изменения не сохранены.",
                    clearDraft=activeDraft!=null,draft=activeDraft,clearDraftGroup=inputGroup)
            }
        }
        fun confirmExit(destination:ScreenAction):EventPlan {
            val resume=if(savedForm?.kind=="paid" || savedForm!=null && savedForm.kind !in setOf("attendance","add_players"))
                ScreenAction("form",inputGroup,savedForm.training,back=savedForm.origin)
                else if(activeDraft!=null) ScreenAction("player",inputGroup,activeDraft.training,user=activeDraft.user,back=activeDraft.origin)
                else ScreenAction("add_players",inputGroup,requireNotNull(savedForm).training,page=savedForm.page,back=savedForm.origin)
            return plan(ScreenAction("exit_confirm",inputGroup,option=signature,back=destination,resume=resume),form=savedForm)
        }
        if(action.kind in setOf("exit_discard","exit_continue")) {
            val destination=action.back ?: ScreenAction("menu",0)
            if(action.option!=signature && unsaved)
                return confirmExit(destination).copy(notice="Ввод обновился. Проверь текущие изменения.")
            if(action.option==signature && action.kind=="exit_continue") return plan(requireNotNull(action.resume),form=savedForm)
            val next=prepare(update,message,user,destination,discardForm=true,discardDraftGroup=inputGroup) ?: return null
            return if(activeDraft==null) next else next.copy(clearDraft=true,draft=activeDraft,clearDraftGroup=inputGroup,nextDraft=next.draft)
        }
        if((exiting || replacing) && unsaved) return confirmExit(action)
        val result = when (action.kind) {
            in PaymentInput.actions -> paymentInput.prepare(user.id,chat,requireNotNull(a),action,savedForm).copy(callback=callback?.id)
            "finance_payment_cancel_save" -> plan(action.back ?: ScreenAction("finance_payment",action.group,action.id),SettlementCommand.CancelPayment(action.id,action.version))
            "finance_payment_edit" -> {
                val auth=requireNotNull(a);val isAdmin=service.isAdmin(auth)
                val t=service.transfer(auth,action.id)
                checkAccounting(canEditPayment(auth,t,isAdmin),ErrorCode.FORBIDDEN,"Изменить сумму может отправитель до подтверждения или администратор группы")
                plan(ScreenAction("form",a.groupId),form=InputForm("payment_edit",a.groupId,transfer=t.id,version=t.version,
                    user=t.to,paymentFrom=t.from,adminPayment=isAdmin,amount=t.amount,waitingForAmount=true,origin=action.back ?: ScreenAction("finance_payment",a.groupId,t.id)))
            }
            "finance_send_save" -> plan(action.back ?: ScreenAction("finance_send",action.group),SettlementCommand.SendPayment(UUID.randomUUID().toString(),action.user,action.value))
            "finance_receive_save" -> {
                checkAccounting(action.version>0,ErrorCode.STALE_VERSION,"Открой перевод заново перед подтверждением")
                plan(ScreenAction("finance_received",action.group),SettlementCommand.ReceivePayment(action.id,action.version))
            }
            "set_training_status" -> {
                val t=service.training(requireNotNull(a),action.id)
                val target=TrainingPhase.valueOf(action.option)
                val command=TrainingLifecycle.command(t,target,action.version)
                plan(trainingScreen(),command)
            }
            "add_player", "remove_player" -> {
                val t=service.training(requireNotNull(a),action.id)
                val command=if(action.kind=="remove_player") SettlementCommand.RemovePlayer(t.id,action.user) else {
                    val member=api.member(action.group,action.user)
                    service.rememberMembership(action.group,action.user,member.present)
                    checkAccounting(member.present,ErrorCode.FORBIDDEN,"Участник больше не состоит в группе. Можно добавить его через Telegram")
                    SettlementCommand.AddPlayers(t.id,t.version,listOf(action.user))
                }
                plan(requireNotNull(action.back),command)
            }
            "pick_add_player" -> formPlan(InputForm("pick_add_player",action.group,action.id,request=(update.id%Int.MAX_VALUE).toInt(),origin=action.back))
            "profile_preview" -> plan(form=savedForm)
            "form" -> plan(form=savedForm)
            "add_players", "toggle_player", "save_players", "pick_players" -> {
                val auth=requireNotNull(a)
                checkAccounting(canEdit(auth,action.id),ErrorCode.FORBIDDEN,"Добавлять может создатель тренировки или администратор этой группы")
                checkAccounting(action.group==discardDraftGroup || state.attendanceDraft(user.id,action.group)==null,ErrorCode.INVALID_STATE,"Сначала заверши открытый ввод игрока кнопкой «Всё правильно»")
                val t=service.training(auth,action.id)
                checkAccounting(t.phase == TrainingPhase.OPEN,ErrorCode.INVALID_STATE,"Тренировка уже учтена")
                val previous=savedForm?.takeIf { it.kind in setOf("add_players","pick_players") && it.group==action.group && it.training==action.id }
                if(action.kind!="add_players" || action.option.isNotEmpty())
                    checkAccounting(previous!=null && state.formSignature(previous)==action.option,ErrorCode.STALE_VERSION,"Выбор изменился. Используй текущие кнопки списка")
                var f=previous?.copy(kind="add_players") ?: InputForm("add_players",action.group,action.id,request=(update.id%Int.MAX_VALUE).toInt(),origin=action.back,
                    order=service.rosterIds(auth).filter { id -> t.players.none { it.userId==id && it.playing } })
                val playing=t.players.filter { it.playing }.map { it.userId }.toSet()
                f=f.copy(selectedUsers=f.selectedUsers.filter { it !in playing },page=action.page)
                if(action.kind=="toggle_player") {
                    service.groupAccount(auth,action.user)
                    checkAccounting(action.user in f.order.orEmpty(),ErrorCode.FORBIDDEN,"Открой актуальный список участников")
                    if(action.user !in playing) f=f.copy(selectedUsers=if(action.user in f.selectedUsers) f.selectedUsers-action.user else f.selectedUsers+action.user)
                }
                if(action.kind=="save_players" && action.version==t.version) {
                    require(f.selectedUsers.isNotEmpty()) { "Выбери хотя бы одного игрока" }
                    plan(f.origin ?: action.copy(kind="roster",page=0,option="players"),SettlementCommand.AddPlayers(t.id,t.version,f.selectedUsers))
                } else if(action.kind=="pick_players") {
                    formPlan(f.copy(kind="pick_players",request=(update.id%Int.MAX_VALUE).toInt()))
                } else plan(action.copy(kind="add_players"),form=f).copy(
                    notice=if(action.kind=="save_players") "Состав изменился. Проверь выбор и подтверди добавление ещё раз." else null)
            }
            in TrainingInput.actions -> trainingInput.prepare(plan(),savedForm,a)
            "participation", "participation_time", "participation_payment", "participation_change" -> {
                val auth=requireNotNull(a)
                val t=service.training(auth,action.id)
                service.requireOpen(t)
                if(action.kind=="participation_time") t.rules.requireChange(AttendanceChange.SET_MINUTES)
                val target=action.user.takeIf { it>0 } ?: user.id
                checkAccounting(target==user.id || service.canEdit(auth,t),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
                if(action.kind=="participation_change") {
                    val type=AttendanceChange.valueOf(action.option)
                    checkAccounting(type in setOf(AttendanceChange.JOIN,AttendanceChange.LEAVE,AttendanceChange.ADJUST_PAID,AttendanceChange.ADJUST_MINUTES,AttendanceChange.ADJUST_GUESTS),ErrorCode.INVALID_INPUT,"Открой актуальные кнопки участия")
                    val destination=(action.resume ?: action.copy(kind="participation")).copy(group=action.group,id=action.id,user=target)
                    plan(destination,SettlementCommand.ChangeAttendance(action.id,target,type,action.value))
                } else plan(action.copy(user=target))
            }
            "player", "change", "reload_attendance" -> {
                val target=action.user.takeIf { it>0 } ?: user.id
                val auth=requireNotNull(a)
                checkAccounting(target==user.id || canEdit(auth,action.id),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
                val existing=if(action.group==discardDraftGroup) null else state.attendanceDraft(user.id,action.group)
                var draft=if (action.kind=="reload_attendance") newAttendanceDraft(auth,action.id,target).copy(returnPage=existing?.returnPage,origin=existing?.origin)
                    else existing ?: newAttendanceDraft(auth,action.id,target)
                        .copy(returnPage=action.page.takeIf { action.option=="roster" },origin=action.back)
                val same=draft.training==action.id && draft.user==target
                if (action.kind=="change" && same)
                    draft=draft.copy(value=service.previewAttendance(draft.value,AttendanceChange.valueOf(action.option),action.value,service.training(auth,action.id).rules))
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
                val destination=if(chat<0) action.copy(kind="close_panel")
                    else draft.origin ?: draft.returnPage?.let { ScreenAction("roster",action.group,action.id,page=it,option="players") } ?: trainingScreen()
                plan(destination,command).copy(clearDraft=true,draft=draft)
            }
            "finish" -> plan(trainingScreen(), SettlementCommand.FinishTraining(action.id, action.version))
            "reopen" -> plan(trainingScreen(), SettlementCommand.ReopenTraining(action.id, action.version))
            "cancel" -> plan(trainingScreen(), SettlementCommand.CancelTraining(action.id, action.version))
            "restore" -> plan(trainingScreen(), SettlementCommand.RestoreTraining(action.id, action.version))
                .copy(notice="Тренировка восстановлена. Проверь данные и нажми «Учесть тренировку».")
            "set_admin" -> {
                if (action.value==1L) {
                    val targetMember=api.member(action.group,action.user)
                    service.rememberMembership(action.group,action.user,targetMember.present)
                    checkAccounting(targetMember.present,ErrorCode.FORBIDDEN,"Назначить можно текущего участника группы")
                }
                plan(action.copy(kind="admin_person"),SettlementCommand.SetAdministrator(action.user,action.value==1L))
            }
            "ask_paid" -> formPlan(InputForm("paid", action.group, action.id, action.user,origin=action.copy(kind="player")))
            "pick_account" -> {
                checkAccounting(canEdit(requireNotNull(a),action.id), ErrorCode.FORBIDDEN, "Добавлять может создатель тренировки или администратор этой группы")
                formPlan(InputForm("pick_account", action.group, action.id, request = (update.id % Int.MAX_VALUE).toInt()))
            }
            else -> plan()
        }
        val writes=result.command!=null || result.defaultUpdate!=null || result.clearDraft || result.draft!=null || result.screen.kind in InputNavigation.effects
        val priorDraft=activeDraft ?: if(writes && !checksInput && inputGroup!=discardDraftGroup) state.attendanceDraft(user.id,inputGroup) else null
        val sameDraft=priorDraft==null || result.draft?.let { it.training==priorDraft.training && it.user==priorDraft.user }==true
        val currentDraftAction=action.kind in InputNavigation.draftActions && inputGroup==action.group && sameDraft &&
            (savedForm==null || savedForm.kind in setOf("paid","attendance") && savedForm.group==action.group && savedForm.training==action.id)
        checkAccounting(!(unsaved || draftDirty(priorDraft)) || !writes || action.kind in InputNavigation.submissions || currentDraftAction,
            ErrorCode.INVALID_STATE,"Сначала заверши или отмени текущий ввод.")
        return if((exiting || replacing) && activeDraft!=null) result.copy(clearDraft=true,draft=activeDraft,clearDraftGroup=inputGroup,nextDraft=result.draft) else result
    }
    private fun canEdit(a:Access,training:String)=service.canEdit(a,service.training(a,training))
    private fun newAttendanceDraft(a: Access, training: String, target: Long): AttendanceDraft {
        checkAccounting(target==a.userId || canEdit(a,training),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
        val t=service.training(a,training)
        service.requireOpen(t)
        val before=t.players.firstOrNull { it.userId==target }
        val value=before ?: Attendance(target,false)
        return AttendanceDraft(training,target,before,value)
    }
    private fun draftDirty(d:AttendanceDraft?):Boolean = d!=null && if(d.expected==null) d.value.playing || d.value.paid!=0L || d.value.guestMinutes!=0L else d.expected!=d.value
    private fun textInput(update: TgUpdate, user: TgUser, chat: Long, f: InputForm, text: String): EventPlan {
        val auth=f.group.takeIf { it<0 }?.let { access(it,user.id) }
        if(PaymentInput.isForm(f)) {
            val input=paymentInput.text(user.id,chat,requireNotNull(auth),f,text)
            val accepted=input.form?.kind=="payment_ready" && input.form.waitingForAmount==false || input.command is SettlementCommand.EditPaymentAmount
            return input.copy(inputMessage=update.message?.id?.takeIf { accepted && chat==user.id && it>0 })
        }
        if(f.kind=="paid") service.requireOpen(service.training(requireNotNull(auth),f.training))
        return when (f.kind) {
            "add_players" -> EventPlan(user.id,chat,ScreenAction("add_players",f.group,f.training,page=f.page),form=f,
                notice="Отметь людей кнопками списка, затем нажми «Добавить».")
            "paid" -> {
                requireNotNull(auth)
                val amount = if (text == "0") 0 else parseAmount(text)
                val draft=state.attendanceDraft(user.id,f.group) ?: newAttendanceDraft(auth,f.training,f.user)
                checkAccounting(draft.training==f.training && draft.user==f.user,ErrorCode.INVALID_STATE,"Сначала заверши уже открытый ввод")
                checkAccounting(f.user==user.id || canEdit(auth,f.training),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
                EventPlan(user.id, chat, ScreenAction("player", f.group, f.training, user = f.user),
                    draft=draft.copy(value=service.previewAttendance(draft.value,AttendanceChange.SET_PAID,amount,service.training(auth,f.training).rules)))
            }
            else -> TrainingInput.text(user.id,chat,f,text)
        }
    }
    private fun answer(callback: TgCallback?, text: String) { callback?.let { runCatching { api.answer(it.id, text.take(180), true) } } }
    /** Freeze the old message identity with the input event, so retries cannot create another reply. */
    private fun positionAfterInput(plan: EventPlan, incomingPrivateMessage: Boolean): EventPlan {
        if (!incomingPrivateMessage || plan.form?.kind in setOf("payment_ready","payment_duplicate","payment_edit") || plan.command is SettlementCommand.EditPaymentAmount) return plan
        val key = "personal:${plan.user}:${plan.chat}"
        val old = state.delivery(key)
        // A new user message permits a fresh reply even if the previous reply never arrived.
        if (old?.status == "UNKNOWN" && old.message == null) state.forgetDelivery(key)
        return plan.copy(newPrivateMessage = true, previousPrivateMessage = old?.message)
    }
    private fun deliver(event: Long, plan: EventPlan, a: Access?) {
        if (plan.screen.kind == "group_menu") {
            val token = state.button(ScreenAction("menu", plan.screen.group), null, "group-link:${plan.screen.group}", true)
            val out = Screens.Output("Открой личное меню бота для тренировок и расчётов.", ButtonAppearance.keyboard(listOf(listOf(TgButton("Открыть меню", url = "https://t.me/${identity.username}?start=n_$token")))), emptySet())
            if(delivery.send("group-menu:${plan.screen.group}", plan.screen.group, plan.chat, null, out, "group-menu:${plan.screen.group}"))
                state.requestGroupMenuPin(plan.screen.group)
            return
        }
        if (plan.chat < 0 && plan.screen.kind in setOf("private_link", "close_panel")) {
            try { closePanel(plan.user, plan.chat, plan.ephemeral,required=true) }
            catch(f:TelegramFailure) {
                if(f.kind in setOf(FailureKind.UNCERTAIN,FailureKind.RETRY_LATER) || f.code in setOf(401,409)) throw f
                plan.callback?.let { runCatching { api.answer(it,"Не удалось закрыть панель. Попробуй ещё раз.",true) } }
                return
            }
            val text = if (plan.screen.kind == "private_link") "Открой актуальные кнопки через «Открыть» на общей карточке." else "Панель закрыта"
            plan.callback?.let { runCatching { api.answer(it, text, plan.screen.kind=="private_link") } }
            return
        }
        val scope = "personal:${plan.user}:${plan.chat}"
        val administrators=if (plan.screen.kind in setOf("administrators","admin_candidates","admin_person")) {
            checkAccounting(a?.telegramAdmin==true,ErrorCode.FORBIDDEN,"Управлять назначениями могут администраторы Telegram-группы")
            api.administrators(plan.screen.group).mapNotNull { it.user }.filterNot { it.isBot }.onEach {
                remember(it);service.rememberMembership(plan.screen.group,it.id,true)
            }.map { it.id }.toSet()+plan.user
        } else emptySet()
        val options=if(plan.screen.kind in setOf("menu","groups","settings") || plan.form?.kind=="group") groupOptions(plan.user,forPublication=plan.form?.kind=="group",discover=plan.screen.kind=="groups" || plan.form?.kind=="group",includeFormer=plan.screen.kind=="groups" && GroupSelection.allowsFormer(plan.screen.option)) else emptyList()
        val out = screens.render(plan.screen, a, scope, plan.user, plan.form, plan.notice, inGroup = plan.chat < 0, telegramAdmins=administrators,groupOptions=options)
        if (plan.chat < 0) {
            // Only training data already visible on the shared card is rendered inside the group.
            val oldTokens = state.activeTokens(scope)
            state.protect(out.tokens)
            try {
                var current = state.currentEphemeral(plan.user, plan.chat) ?: plan.ephemeral
                // A shared-card callback opens a fresh panel; callbacks inside a panel edit it.
                // Successful sends persist their ID in the event plan, so a retry only edits.
                if(plan.ephemeral==null && current!=null) {
                    closePanel(plan.user,plan.chat,current,required=true)
                    current=null
                    state.protect(out.tokens)
                }
                var updated = false
                if (current != null) {
                    try {
                        if(out.richHtml!=null) api.editEphemeralRich(plan.chat,plan.user,current,out.text,out.richHtml,out.keyboard)
                        else api.editEphemeral(plan.chat, plan.user, current, out.text, out.keyboard)
                        state.rememberEphemeral(plan.user, plan.chat, current)
                        updated = true
                    } catch (failure: TelegramFailure) {
                        if (failure.kind != FailureKind.MESSAGE_MISSING) throw failure
                    }
                }
                if (!updated) {
                    val sent = if(out.richHtml!=null) api.ephemeralRich(plan.chat,plan.user,requireNotNull(plan.callback),out.text,out.richHtml,out.keyboard)
                        else api.ephemeral(plan.chat, plan.user, requireNotNull(plan.callback), out.text, out.keyboard)
                    state.rememberEphemeral(plan.user, plan.chat, sent.ephemeralId,event)
                    if (current != null && current != sent.ephemeralId) deletePanel(plan.user, plan.chat, current)
                }
                listOfNotNull(plan.ephemeral).distinct()
                    .filter { it != current && it != state.currentEphemeral(plan.user, plan.chat) }
                    .forEach { deletePanel(plan.user, plan.chat, it) }
                state.replace(scope, out.tokens)
                state.panel(plan.user,plan.chat,plan.screen.takeIf { it.kind.startsWith("participation") || it.kind in setOf("poll_detail","poll_close_confirm") },out.trainingVersion)
            } catch (failure: TelegramFailure) {
                if (failure.code in setOf(401, 409)) throw failure
                System.err.println("Telegram: персональная панель не доставлена; ${failure.kind}, код=${failure.code ?: "нет"}")
                if (failure.kind != FailureKind.UNCERTAIN) state.replace(scope, oldTokens)
                val notice = (if (plan.command != null) "Изменение сохранено. " else "") +
                    if(plan.screen.kind.startsWith("poll_")) "Панель не открылась. Открой личный чат с ботом и выбери «Мои опросы»." else "Панель не открылась. Открой личный чат с ботом и выбери «Мои тренировки»."
                plan.callback?.let { runCatching { api.answer(it, notice, true) } }
                if (failure.kind == FailureKind.RETRY_LATER) throw failure
                return
            }
        } else {
            val key = "personal:${plan.user}:${plan.chat}"
            val old = state.delivery(key)
            if (plan.newPrivateMessage && old?.message!=null && old.message == plan.previousPrivateMessage) {
                state.retirePrivateMenu(plan.user,plan.chat,old.message)
                state.forgetDelivery(key)
            }
            if(delivery.send(key, plan.screen.group, plan.chat, plan.user, out, scope)) {
                val view=if(plan.screen.kind in setOf("poll_detail","poll_close_confirm"))
                    state.delivery(key)?.message?.let { PrivatePollView(it,plan.screen,privatePollRevision(plan.screen)) } else null
                state.privatePollView(plan.user,plan.chat,view?.takeUnless { it.revision.startsWith("CLOSED:") })
                delivery.retirePrivateMenus(plan.user)
            }
            if (plan.form?.kind in setOf("pick_account","pick_players","pick_add_player")) {
                requireNotNull(plan.form)
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
    private fun deletePanel(user: Long, chat: Long, id: Long, required:Boolean=false): Boolean {
        try { api.deleteEphemeral(chat, user, id); return true }
        catch (failure: TelegramFailure) {
            if (failure.kind == FailureKind.MESSAGE_MISSING) return true
            if (failure.code in setOf(401,409)) throw failure
            System.err.println("Telegram: закрытие персональной панели; ${failure.kind}, код=${failure.code ?: "нет"}")
            if(required) throw failure
            return false
        }
    }
    private fun closePanel(user: Long, chat: Long, id: Long? = null, required:Boolean=false) {
        val current = state.currentEphemeral(user, chat)
        val target = id ?: current ?: return
        if (!deletePanel(user, chat, target,required)) return
        if (current == null || current == target) {
            state.rememberEphemeral(user, chat, null)
            state.replace("personal:$user:$chat", emptySet())
        }
    }
    fun hasClosingPolls()=polls.hasClosingPolls()
    /** Run after an empty getUpdates response, so votes queued before stopPoll are included. */
    fun finishPollsAfterDrain() {
        polls.closingPolls().forEach { polls.finish(it) }
    }
    fun maintain() {
        checkedMembership.clear()
        val now = clock.instant().epochSecond
        if (lastCleanup == Long.MIN_VALUE || now - lastCleanup >= 30) { state.cleanup(); lastCleanup = now }
        polls.maintenancePolls().forEach { p ->
            if(p.status=="OPEN" && p.message!=null) pollWorkflow.recordDelivery(p)
            if(p.status=="CLOSING") try { pollWorkflow.stop(p) } catch(f:TelegramFailure) {
                if(f.kind!=FailureKind.REJECTED || f.code in setOf(401,409)) throw f
            }
        }
        state.reconcilePins()
        unpinCards()
        delivery.retirePrivateMenus()
        state.pendingCards().filter { (group,training,_) ->
            (cardRetries[group to training] ?: 0)<=clock.millis() && canRefreshCard(group,training)
        }.take(2).forEach { (group,training,through) ->
            try {
                if(refreshCard(group,training)) { state.cardDelivered(group,training,through);cardRetries.remove(group to training) }
            } catch(f:TelegramFailure) {
                if(f.code in setOf(401,409)) throw f
                cardRetries[group to training]=clock.millis()+(f.retryAfter ?: 5).coerceIn(1,60)*1000L
                if(f.kind==FailureKind.RETRY_LATER) throw f
            }
        }
        refreshPendingPanels()
        refreshPrivatePollViews()
        state.completedPollPanels().forEach { (user,group,id) -> closePanel(user,group,id) }
        pinCards()
        paymentNotifications.maintain()
        delivery.removePaymentNotices()
    }
    private fun privatePollRevision(screen:ScreenAction):String {
        val p=polls.get(screen.group,screen.id)
        return "${p.status}:${polls.count(p)}:${service.groupTrainingRules(p.group).trackTime}"
    }
    private fun refreshPrivatePollViews() {
        state.privatePollViews().forEach { (user,chat,view) ->
            val key="personal:$user:$chat"
            if(state.delivery(key)?.message!=view.message) {
                state.privatePollView(user,chat,null);return@forEach
            }
            try {
                val revision=privatePollRevision(view.screen)
                if(revision==view.revision) return@forEach
                val auth=access(view.screen.group,user)
                val out=screens.render(view.screen,auth,key,user)
                if(delivery.send(key,view.screen.group,chat,user,out,key,recreateMissing=false))
                    state.privatePollView(user,chat,if(state.delivery(key)?.message==view.message && !revision.startsWith("CLOSED:")) view.copy(revision=revision) else null)
            } catch(f:AccountingException) {
                if(f.code!=ErrorCode.FORBIDDEN && f.code!=ErrorCode.INVALID_INPUT) throw f
                state.privatePollView(user,chat,null)
            } catch(f:TelegramFailure) {
                if(f.kind==FailureKind.REJECTED && f.code in setOf(400,403)) state.privatePollView(user,chat,null)
                else throw f
            }
        }
    }
    private fun unpinCards() {
        state.pendingUnpins().forEach { (key,delivery) ->
            state.pinStatus(key,"UNPIN_SENDING")
            try { api.unpin(delivery.chat,requireNotNull(delivery.message));state.pinStatus(key,"UNPINNED") }
            catch(f:TelegramFailure) {
                when(f.kind) {
                    FailureKind.MESSAGE_MISSING,FailureKind.NOT_MODIFIED -> state.pinStatus(key,"UNPINNED")
                    FailureKind.UNCERTAIN,FailureKind.RETRY_LATER -> { state.pinStatus(key,"UNPIN_PENDING");throw f }
                    else -> { state.pinStatus(key,"UNPIN_FAILED");if(f.code in setOf(401,409)) throw f }
                }
            }
        }
    }
    private fun pinCards() {
        state.pendingPins().forEach { (key,delivery) ->
            val groupMenu=key=="group-menu:${delivery.chat}"
            state.pinStatus(key,"SENDING")
            try { api.pin(delivery.chat,requireNotNull(delivery.message),silent=groupMenu);state.pinStatus(key,"SENT") }
            catch(f:TelegramFailure) {
                // Menu pins are silent, so uncertain requests can be retried without repeated notifications.
                val retry=f.kind==FailureKind.RETRY_LATER || groupMenu && f.kind==FailureKind.UNCERTAIN
                state.pinStatus(key,when { retry -> "PENDING";f.kind==FailureKind.UNCERTAIN -> "UNKNOWN";else -> "FAILED" })
                if(retry || f.code in setOf(401,409)) throw f
            }
        }
    }
    /** Progress is stored in panel_json, so unfinished refreshes survive a restart. */
    private fun refreshPendingPanels() {
        state.pendingPanels().forEach { panel ->
            val (user,group,id,screen)=panel
            val scope="personal:$user:$group"
            val oldTokens=state.activeTokens(scope)
            try {
                val auth=access(group,user)
                val t=service.training(auth,screen.id)
                if(t.phase != TrainingPhase.OPEN) closePanel(user,group,id,required=true)
                else {
                    val player=t.players.firstOrNull { it.userId==user }
                    val available=player?.playing==true || screen.kind=="participation_payment" && (player?.paid ?: 0)>0
                    val shown=if(available && (screen.kind!="participation_time" || t.rules.trackTime)) screen else screen.copy(kind="participation")
                    val out=screens.render(shown,auth,scope,user,inGroup=true)
                    state.protect(out.tokens)
                    api.editEphemeralRich(group,user,id,out.text,requireNotNull(out.richHtml),out.keyboard)
                    state.replace(scope,out.tokens)
                    state.panel(user,group,shown,out.trainingVersion)
                }
            } catch(f:AccountingException) {
                if(f.code!=ErrorCode.FORBIDDEN) throw f
                closePanel(user,group,id)
                if(state.currentEphemeral(user,group)!=null) state.retryPanel(panel,30_000)
            } catch(f:TelegramFailure) {
                if(f.code in setOf(401,409)) throw f
                if(f.kind==FailureKind.MESSAGE_MISSING) {
                    state.rememberEphemeral(user,group,null);state.replace(scope,emptySet())
                } else {
                    if(f.kind==FailureKind.REJECTED) state.replace(scope,oldTokens)
                    state.retryPanel(panel,if(f.kind==FailureKind.RETRY_LATER) (f.retryAfter ?: 3).coerceIn(1,60)*1000L else if(f.kind==FailureKind.REJECTED) 30_000 else 5_000)
                    if(f.kind==FailureKind.RETRY_LATER) throw f
                }
            }
        }
    }
    fun hasPendingPanelRefreshes()=state.pendingPanels(1).isNotEmpty()
    private fun canRefreshCard(group:Long,training:String):Boolean {
        val delivery=state.delivery("training:$group:$training")
        return delivery?.status!="FAILED" && (delivery?.status!="UNKNOWN" || delivery.message!=null)
    }
    fun nextPollTimeout(maximum:Int):Int {
        if(hasClosingPolls()) return 0
        var wait=state.panelRefreshDelay(maximum)
        if(state.hasPendingPaymentNotices()) wait=minOf(wait,1)
        state.pendingCards().forEach { (group,training,_) ->
            if(canRefreshCard(group,training)) {
                val due=cardRetries[group to training] ?: 0
                wait=minOf(wait,((due-clock.millis()).coerceAtLeast(0)/1000+1).coerceAtMost(maximum.toLong()).toInt())
            }
        }
        return wait
    }
    private fun refreshCard(group: Long, training: String, allowCreate:Boolean?=null): Boolean {
        val author = service.database.read { c -> sqlQuery(c, "SELECT created_by FROM trainings WHERE group_id=? AND id=?", group, training) { it.getLong(1) }.single() }
        // Public information for its original chat. No private context or privileges are used for background delivery.
        val scope = "training:$group:$training"
        val out = screens.render(ScreenAction("public", group, training), Access(group, author), scope, null)
        val create=allowCreate ?: (service.training(Access(group,author),training).phase==TrainingPhase.OPEN)
        return delivery.send(scope, group, group, null, out, scope,create)
    }
}
