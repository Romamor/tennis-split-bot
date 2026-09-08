package ru.movereon.tennis.telegram

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

class TelegramBot(private val api: TelegramApi, private val accounting: SqliteAccountingStore,
    val identity: TgUser, private val clock: Clock = Clock.systemUTC(), private val defaultZone: String = "Europe/Moscow") {
    val state = TelegramStore(accounting)
    private val service = GroupService(accounting,clock)
    private val membership = Membership(api,state,identity)
    val delivery = TelegramDelivery(api,state,accounting,service,identity)
    private val ui = BotUi(service,accounting,state,clock)
    init { state.bind(identity) }

    /** A single poller calls this sequentially. Failures leave the offset unchanged for retry. */
    fun handle(update: TgUpdate) {
        if(state.completed(update.id)) return
        val message = update.message
        if(message != null && message.chat.type in setOf("group","supergroup")) {
            handleGroup(update.id,message)
            state.complete(update.id)
            return
        }
        val user = update.callback?.from ?: message?.from
        if(user == null || user.isBot) { state.complete(update.id); return }
        val chat = update.callback?.message?.chat ?: message?.chat
        if(chat?.type != "private" || chat.id != user.id) { state.complete(update.id); return }
        state.remember(user)
        val previousSession = state.session(user.id)
        val moveToBottom = message != null || previousSession.input != null ||
            update.callback?.message?.id != previousSession.panelId
        update.callback?.let { try { api.answer(it.id) } catch (_: TelegramFailure) { /* Callback acknowledgement is not a financial operation. */ } }
        try {
            val saved = state.plan(update.id)
            val plan = saved ?: resolve(update,user) ?: run { state.complete(update.id); return }
            val member = membership.verify(plan.groupId,user.id)
            state.savePlan(update.id,plan)
            execute(update.id, member, plan, moveToBottom)
        } catch (_: BotAccessDenied) {
            privatePanel(user.id,null,update.id,"Доступ к группе не подтверждён. Проверь, что ты остаёшься в чате, а бот имеет права администратора.",null,moveToBottom)
        } catch (failure: SimilarTransferFound) {
            val plan = requireNotNull(state.plan(update.id))
            val command = plan.action.command as WorkflowCommand.RecordTransfer
            val screen = Screen("Похожий перевод уже записан. Проверь его, прежде чем добавлять ещё один.",
                failure.transferIds.take(5).map { listOf("Посмотреть запись" to BotAction("transfer",entity=it)) } +
                    listOf(listOf("Это ещё один перевод" to plan.action.copy(command=command.copy(allowSimilar=true))), listOf("В меню" to BotAction("menu"))))
            show(user.id,plan.groupId,update.id,screen,moveToBottom)
        } catch (failure: AccountingException) {
            val plan = state.plan(update.id)
            if(plan != null) show(user.id,plan.groupId,update.id,Screen(explain(failure),listOf(listOf("Открыть свежую запись" to errorBack(plan.action)),listOf("В меню" to BotAction("menu")))),moveToBottom)
            else privatePanel(user.id,state.session(user.id).groupId,update.id,explain(failure),null,moveToBottom)
        } catch (_: NoSuchElementException) {
            val group = state.session(user.id).groupId
            if(group != null) show(user.id,group,update.id,Screen("Запись уже изменилась. Открой её заново.",listOf(listOf("В меню" to BotAction("menu")))),moveToBottom)
        }
        state.complete(update.id)
    }

    private fun handleGroup(updateId: Long, message: TgMessage) {
        if(message.migrateTo != null) { state.migrate(message.chat.id,message.migrateTo); return }
        val user = message.from ?: return
        if(user.isBot) return
        val first = message.text?.trim()?.substringBefore(' ')?.lowercase() ?: return
        val command = when(first) {
            "/setup", "/setup@${identity.username?.lowercase()}" -> "setup"
            "/restore", "/restore@${identity.username?.lowercase()}" -> "restore"
            else -> return // No ordinary group chat content is persisted.
        }
        state.remember(user)
        val botMember: TgMember
        val human: TgMember
        try { botMember = api.member(message.chat.id,identity.id); human = api.member(message.chat.id,user.id) }
        catch(failure: TelegramFailure) {
            if(failure.kind != FailureKind.REJECTED || failure.code !in setOf(400,403)) throw failure
            delivery.sendOnce("setup-help:$updateId",null,user.id,"Не удалось проверить доступ к группе. Добавь бота администратором и повтори /setup.")
            return
        }
        if(!botMember.admin || !human.admin && command == "setup" || !human.present) {
            delivery.sendOnce("setup-help:$updateId",null,user.id,"Для настройки бот и настраивающий участник должны быть администраторами группы. Затем отправь /setup в группе.")
            return
        }
        if(command == "setup") {
            val group = state.groupByChat(message.chat.id) ?: BotGroup("tg:${message.chat.id}",message.chat.id,message.chat.title ?: "Теннис")
            if(!state.initialized(group.id)) service.execute(VerifiedGroupMember(group.id,user.id,true),"telegram-setup:$updateId",WorkflowCommand.CreateGroup(defaultZone))
            state.register(group)
            delivery.groupMenu(group.id)
        } else {
            val group = state.groupByChat(message.chat.id) ?: return
            val member = membership.verify(group.id,user.id)
            if(!service.canManage(member) && !member.isTelegramAdmin) return
            val reply = message.replyTo ?: return
            if(reply.from?.id != identity.id || reply.chat.id != message.chat.id) return
            val link = reply.keyboard?.rows?.flatten()?.mapNotNull { it.url?.substringAfter("?start=","")?.let(state::link) }
                ?.firstOrNull { it.groupId == group.id && it.action.kind in setOf("menu","draft") } ?: return
            val key = if(link.action.kind == "menu") "menu:${group.id}" else "training:${group.id}:${link.action.entity}"
            if(state.delivery(key)?.groupId != group.id) return
            state.deliveryStatus(key,"SENT",reply.id)
            if(link.action.kind == "menu") delivery.groupMenu(group.id) else delivery.trainingCard(group.id,requireNotNull(link.action.entity))
        }
    }

    private fun resolve(update: TgUpdate,user: TgUser): SavedPlan? {
        update.callback?.let { callback ->
            val action = callback.data?.let(state::action)
            if(action == null || action.userId != user.id) throw BotAccessDenied()
            return SavedPlan(user.id,action.groupId,action.token,action.action)
        }
        val message = requireNotNull(update.message)
        val text = message.text?.trim() ?: return null
        if(text == "/start" || text.startsWith("/start ") || text == "/menu") {
            val parameter = text.substringAfter(' ',"").trim()
            val link = if(parameter.isNotEmpty()) state.link(parameter) ?: throw BotAccessDenied() else null
            val groupId = link?.groupId ?: state.session(user.id).groupId
            if(groupId == null) {
                privatePanel(user.id,null,update.id,"Добавь бота в группу и дай ему права администратора. Администратор группы отправляет /setup. Затем открой любую кнопку в появившемся меню.",null,moveToBottom=true)
                return null
            }
            return SavedPlan(user.id,groupId,"start:${update.id}",link?.action ?: BotAction("menu"))
        }
        val session = state.session(user.id)
        val pending = session.input
        if(pending == null || session.groupId == null || message.replyTo?.id != pending.promptId || message.replyTo.from?.id != identity.id) {
            privatePanel(user.id,session.groupId,update.id,"Для ввода данных ответь на последнее сообщение-запрос бота. Меню можно открыть командой /menu.",null,moveToBottom=true)
            return null
        }
        membership.verify(session.groupId,user.id)
        return try { SavedPlan(user.id,session.groupId,"text:${update.id}",parseInput(pending.action,pending.token,text)) }
        catch (failure: AccountingException) {
            if(pending.action.field in setOf("all_minutes","minutes"))
                return SavedPlan(user.id,session.groupId,"text:${update.id}",pending.action.copy(kind="time_choices"))
            prompt(user.id,session.groupId,update.id,pending.token,pending.action,explain(failure))
            null
        }
    }

    private fun execute(updateId: Long,member: VerifiedGroupMember,plan: SavedPlan,moveToBottom: Boolean) {
        var action = plan.action
        val session = state.session(member.userId)
        state.saveSession(session.copy(groupId=member.groupId,input=null))
        when(action.kind) {
            "ask" -> {
                if(action.field in setOf("all_minutes","minutes")) {
                    show(member.userId,member.groupId,updateId,ui.render(member,action.copy(kind="time_choices")),moveToBottom)
                    return
                }
                prompt(member.userId,member.groupId,updateId,plan.token,action); return
            }
            "create_draft" -> {
                val id = "d_${plan.token}"
                service.execute(member,"telegram:${plan.token}",WorkflowCommand.CreateDraft(id,requireNotNull(action.field)))
                action = BotAction("draft",entity=id)
            }
            "new_transfer" -> {
                val own = service.participants(member,true).firstOrNull { it.participant.telegramUserId == member.userId }?.participant
                action = BotAction("form",form=TransferForm("t_${plan.token}",from=own?.id,date=ui.today(member)))
            }
            "apply" -> {
                val command = requireNotNull(action.command)
                val represented = when(command) {
                    is WorkflowCommand.RecordTransfer -> command.onBehalfOf
                    is WorkflowCommand.ChangeTransfer -> command.onBehalfOf
                    else -> null
                }
                val representedUser = service.participants(member,true).firstOrNull { it.participant.id == represented }?.participant?.telegramUserId
                val absent = if(representedUser != null && representedUser != member.userId) membership.absent(member.groupId,representedUser) else null
                service.execute(member,"telegram:${plan.token}",command,absent)
                delivery.flush()
                action = BotAction(action.back ?: "menu",entity=action.entity,participant=action.participant,form=action.form,represented=represented)
            }
            "recover" -> {
                checkAccounting(service.canManage(member),ErrorCode.FORBIDDEN,"Organizer required")
                delivery.recovery(requireNotNull(action.entity),member.groupId,plan.token)
                action = BotAction("recovery")
            }
        }
        show(member.userId,member.groupId,updateId,ui.render(member,action),moveToBottom)
    }

    private fun parseInput(action: BotAction, token: String, text: String): BotAction {
        checkAccounting(text.length <= 1000,ErrorCode.INVALID_INPUT,"Input too long")
        fun date(): String {
            val parsed = runCatching { LocalDate.parse(text) }.getOrNull() ?: runCatching {
                LocalDate.parse(text,DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT))
            }.getOrNull()
            checkAccounting(parsed != null,ErrorCode.INVALID_INPUT,"Invalid date")
            return parsed.toString()
        }
        return when(action.field) {
            "name" -> {
                val id = "p_$token"
                BotAction("apply",command=WorkflowCommand.AddParticipant(id,text),back=action.back,
                    entity=if(action.back == "profile") id else action.entity)
            }
            "rename" -> BotAction("apply",entity=action.entity,command=WorkflowCommand.RenameParticipant(requireNotNull(action.entity),requireNotNull(action.version),text),back="profile")
            "transfer_amount", "transfer_date", "transfer_note" -> {
                val form = requireNotNull(action.form)
                val updated = when(action.field) {
                    "transfer_amount" -> form.copy(amount=parseAmount(text))
                    "transfer_date" -> form.copy(date=date())
                    else -> { checkAccounting(text.length <= 300,ErrorCode.INVALID_INPUT,"Note too long"); form.copy(note=if(text == "-") null else text) }
                }
                BotAction("form",form=updated)
            }
            else -> {
                val old = requireNotNull(action.draft)
                val content = when(action.field) {
                    "draft_date" -> old.copy(date=date())
                    "all_minutes" -> old.copy(players=old.players.map { it.copy(minutes=parseAmount(text)) })
                    "minutes" -> old.copy(players=old.players.map {
                        if(it.participantId == action.participant && it.plusOne == action.guest) it.copy(minutes=parseAmount(text)) else it
                    })
                    "payment" -> old.copy(payments=old.payments.filterNot { it.participantId == action.participant } +
                        if(text == "0") emptyList() else listOf(PaymentInput(requireNotNull(action.participant),parseAmount(text))))
                    else -> throw AccountingException(ErrorCode.INVALID_INPUT,"Unknown input")
                }
                BotAction("apply",entity=action.entity,participant=action.participant,command=WorkflowCommand.SaveDraft(requireNotNull(action.entity),requireNotNull(action.version),content),back=action.back)
            }
        }
    }

    private fun prompt(userId: Long,groupId: String,updateId: Long,token: String,action: BotAction,error: String? = null) {
        val instruction = when(action.field) {
            "name", "rename" -> "Напиши имя участника, до 100 символов."
            "draft_date", "transfer_date" -> "Укажи дату: ДД.ММ.ГГГГ или ГГГГ-ММ-ДД."
            "payment" -> "Сколько рублей оплатил этот человек? Целое число. 0 — убрать оплату."
            "transfer_amount" -> "Сколько рублей перевели? Укажи целую сумму."
            "transfer_note" -> "Комментарий к переводу, до 300 символов. Напиши - для пустого комментария."
            else -> "Введи значение."
        }
        val id = delivery.sendOnce("prompt:$userId:$updateId",groupId,userId,listOfNotNull(error,instruction,"Ответь именно на это сообщение.").joinToString("\n"),forceReply=true)
        if(id != null) state.saveSession(state.session(userId).copy(groupId=groupId,input=PendingInput(token,action,id)))
    }
    private fun show(userId: Long,groupId: String,updateId: Long,screen: Screen,moveToBottom: Boolean) {
        val keyboard = TgKeyboard(screen.buttons.map { row -> row.map { (label,action) -> TgButton(label.take(60),callbackData=state.action(userId,groupId,action)) } })
        privatePanel(userId,groupId,updateId,screen.text,keyboard,moveToBottom)
    }
    private fun privatePanel(userId: Long,groupId: String?,updateId: Long,text: String,keyboard: TgKeyboard?,moveToBottom: Boolean) {
        val session = state.session(userId)
        val previousPanelId = session.panelId
        var panelId = if (moveToBottom) null else previousPanelId
        if(panelId != null) {
            try { api.edit(userId,panelId,text.take(4000),keyboard) }
            catch (failure: TelegramFailure) {
                if(failure.kind in setOf(FailureKind.REJECTED,FailureKind.MESSAGE_MISSING)) panelId=null else throw failure
            }
        }
        if(panelId == null) panelId = delivery.sendOnce("panel:$userId:$updateId",groupId,userId,text,keyboard)
        state.saveSession(session.copy(groupId=groupId,panelId=panelId))
        if (moveToBottom && panelId != null && previousPanelId != null && panelId != previousPanelId) {
            // Retire old controls only after the new panel is confirmed delivered.
            // Cleanup failure must not retry the user's already completed action.
            try { api.edit(userId,previousPanelId,"Продолжение — в сообщении ниже ↓",null) }
            catch (_: TelegramFailure) { /* The new panel remains available. */ }
        }
    }
    private fun explain(error: AccountingException): String = when(error.code) {
        ErrorCode.STALE_VERSION -> "Запись уже изменена. Открой свежую версию и повтори правку."
        ErrorCode.FORBIDDEN -> "Для этого действия не хватает прав. Перевод отмечает одна из его сторон; учёт чужой тренировки выполняет организатор."
        ErrorCode.COMMAND_CONFLICT -> "Эта кнопка уже использована с другими данными. Открой меню заново."
        ErrorCode.INVALID_STATE -> "Состояние записи уже изменилось. Проверь актуальные данные."
        ErrorCode.OUT_OF_RANGE -> "Слишком большое число. Проверь сумму или время."
        ErrorCode.UNBALANCED -> "Расчёт не сошёлся. Действие не сохранено."
        ErrorCode.INVALID_INPUT -> when {
            error.message.orEmpty().contains("Future",true) -> "Эта дата ещё не наступила. Можно сохранить черновик и учесть его позже."
            error.message.orEmpty().contains("date",true) -> "Проверь дату. Например: 08.09.2026."
            error.message.orEmpty().contains("Players and payments",true) -> "Добавь игроков, время и хотя бы одну оплату."
            error.message.orEmpty().contains("Pick players",true) -> "Сначала добавь игроков в тренировку, затем задай общее время."
            else -> "Проверь данные: суммы — в целых рублях, время — с шагом полчаса, имя — до 100 символов."
        }
    }

    private fun errorBack(action: BotAction): BotAction {
        val command = action.command
        if(command is WorkflowCommand.RecordTransfer) return BotAction("form",form=TransferForm(command.id,command.from,command.to,command.amount,command.date,command.note,command.onBehalfOf))
        if(command is WorkflowCommand.AddParticipant) return BotAction("participants")
        return BotAction(action.back ?: if(action.entity != null && action.kind in setOf("draft","preview","players","player","payments")) "draft" else "menu",
            entity=action.entity,participant=action.participant,form=action.form)
    }
}
