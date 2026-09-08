package ru.movereon.tennis.telegram

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.Command
import ru.movereon.tennis.storage.SqliteAccountingStore

class BotAccessDenied : IllegalStateException("Доступ к группе не подтверждён")

class Membership(private val api: TelegramApi, private val state: TelegramStore, private val bot: TgUser) {
    fun verify(groupId: String, userId: Long): VerifiedGroupMember {
        val group = state.group(groupId) ?: throw BotAccessDenied()
        if (!lookup(group.chatId, bot.id).admin) throw BotAccessDenied()
        val member = lookup(group.chatId, userId)
        if (!member.present) throw BotAccessDenied()
        return VerifiedGroupMember(groupId, userId, member.admin)
    }
    fun absent(groupId: String, userId: Long): VerifiedAbsentMember? {
        val group = state.group(groupId) ?: throw BotAccessDenied()
        return if (lookup(group.chatId, userId).absent) VerifiedAbsentMember(groupId, userId) else null
    }
    private fun lookup(chatId: Long,userId: Long): TgMember = try { api.member(chatId,userId) }
    catch(failure: TelegramFailure) {
        if(failure.kind == FailureKind.REJECTED && failure.code in setOf(400,403)) throw BotAccessDenied()
        throw failure
    }
}

/** Persistent send intents. Unknown send outcomes are paused, never retried automatically. */
class TelegramDelivery(private val api: TelegramApi, private val state: TelegramStore,
    private val accounting: SqliteAccountingStore, private val service: GroupService, private val bot: TgUser) {
    private val membership = Membership(api, state, bot)
    fun url(groupId: String, action: BotAction): String = "https://t.me/${bot.username}?start=${state.link(groupId, action)}"

    fun sendOnce(key: String, groupId: String?, chatId: Long, text: String, keyboard: TgKeyboard? = null, forceReply: Boolean = false): Long? {
        val previous = state.delivery(key)
        if (previous?.status == "SENT") return previous.messageId
        if (previous?.status in setOf("SENDING", "UNKNOWN", "FAILED", "BLOCKED")) return null
        state.sending(key, groupId, chatId)
        return try {
            val sent = api.send(chatId, text.take(4000), keyboard, forceReply)
            state.deliveryStatus(key, "SENT", sent.id)
            sent.id
        } catch (failure: TelegramFailure) {
            state.deliveryStatus(key, when(failure.kind) {
                FailureKind.RETRY_LATER -> "RETRY"
                FailureKind.UNCERTAIN -> "UNKNOWN"
                else -> if (failure.code == 403) "BLOCKED" else "FAILED"
            })
            if (failure.kind == FailureKind.RETRY_LATER) throw failure
            null
        }
    }

    fun groupMenu(groupId: String) {
        val group = requireNotNull(state.group(groupId))
        val keyboard = TgKeyboard(listOf(
            listOf(TgButton("Добавить тренировку", url = url(groupId, BotAction("drafts")))),
            listOf(TgButton("Балансы", url = url(groupId, BotAction("balances"))), TgButton("Отметить перевод", url = url(groupId, BotAction("new_transfer")))),
            listOf(TgButton("История", url = url(groupId, BotAction("history"))), TgButton("Открыть меню", url = url(groupId, BotAction("menu")))),
        ))
        upsertGroup("menu:$groupId", group, "🏓 Расчёты нашей группы\n\nРасходы и возвраты сохраняются между тренировками. Кнопки открывают личную переписку с ботом.", keyboard)
    }

    fun trainingCard(groupId: String, draftId: String) {
        val group = requireNotNull(state.group(groupId))
        val member = membership.verify(groupId, bot.id)
        val draft = service.draft(member, draftId)
        val published = draft.publishedContent ?: return
        val names = participantLabels(service.participants(member,true).map { it.participant })
        val main = published.players.filterNot { it.plusOne }
        val namesLine = main.take(6).joinToString(", ") {
            names[it.participantId].orEmpty().take(40) + if (published.players.any { p -> p.participantId == it.participantId && p.plusOne }) " +1" else ""
        } + if (main.size > 6) " и ещё ${main.size - 6}" else ""
        val time = published.players.map { it.minutes }.distinct().let { if (it.size == 1) "по ${it.single()} мин" else "время различается" }
        val total = published.payments.sumOf { it.amount }
        val payers = published.payments.take(6).joinToString(", ") { "${names[it.participantId].orEmpty().take(40)} ${it.amount} ₽" } +
            if(published.payments.size > 6) " и ещё ${published.payments.size - 6}" else ""
        val status = if (draft.status == DraftStatus.CANCELLED) "Отменена" else "Учтено"
        val text = "🏓 ${published.date} · $total ₽\n$namesLine · $time\nОплатили: $payers\n$status · записал(а) ${state.name(draft.createdBy)}"
        val keyboard = TgKeyboard(listOf(listOf(
            TgButton("Подробности / исправить", url = url(groupId, BotAction("draft", entity = draftId))),
            TgButton("Мой расчёт", url = url(groupId, BotAction("balances"))),
        )))
        upsertGroup("training:$groupId:$draftId", group, text, keyboard)
    }

    private fun upsertGroup(key: String, group: BotGroup, text: String, keyboard: TgKeyboard) {
        val saved = state.delivery(key)
        if (saved?.messageId != null && saved.status == "SENT") {
            try { api.edit(group.chatId, saved.messageId, text.take(4000), keyboard) }
            catch (failure: TelegramFailure) {
                // An edit is safe to retry; a missing message needs explicit recovery.
                if (failure.kind == FailureKind.MESSAGE_MISSING || failure.kind == FailureKind.REJECTED) state.deliveryStatus(key, "FAILED")
                else throw failure
            }
        } else sendOnce(key, group.id, group.chatId, text, keyboard)
    }

    fun flush() {
        for (event in state.groups().flatMap { accounting.pendingEvents(groupId=it.id) }) {
            try {
                val observer = membership.verify(event.groupId, bot.id)
                if (event.kind.startsWith("training_")) trainingCard(event.groupId, event.entityId)
                else {
                    val history = accounting.history(event.groupId)
                    val operation = history.single { it.receipt.sequence == event.sequence }
                    val original = history.firstOrNull { it.command is Command.RecordTransfer && it.command.entityId == event.entityId }
                        ?: error("Transfer source is missing")
                    val transfer = original.command as Command.RecordTransfer
                    val profiles = service.participants(observer, true).associate { it.participant.id to it.participant }
                    val label = when(event.kind) {
                        "transfer_recorded" -> "Перевод учтён"
                        "transfer_under_review" -> "Уточняем перевод: пока не учитываем его в расчётах"
                        "transfer_confirmed" -> "Перевод снова учтён"
                        else -> "Ошибочная запись отменена"
                    }
                    val text = "$label\n${profiles[transfer.from.value]?.name} → ${profiles[transfer.to.value]?.name}: ${transfer.amount} ₽\nЗаписал(а): ${state.name(operation.actor.id.removePrefix("telegram:").toLongOrNull() ?: 0)}"
                    val users = listOfNotNull(profiles[transfer.from.value]?.telegramUserId, profiles[transfer.to.value]?.telegramUserId).distinct()
                    for (user in users) {
                        if (operation.actor.id == "telegram:$user") continue
                        val key = "notice:${event.groupId}:${event.sequence}:$user"
                        try { membership.verify(event.groupId, user) }
                        catch (_: BotAccessDenied) { state.sending(key, event.groupId, user); state.deliveryStatus(key, "BLOCKED"); continue }
                        val keyboard = TgKeyboard(listOf(listOf(TgButton("Посмотреть перевод", url = url(event.groupId, BotAction("transfer", entity = event.entityId))))))
                        sendOnce(key, event.groupId, user, text, keyboard)
                    }
                }
                accounting.acknowledge(event.groupId, event.sequence)
            } catch (_: BotAccessDenied) { /* Keep pending until bot access is restored. */ }
        }
    }

    fun recovery(key: String, groupId: String, token: String) {
        require(key == "menu:$groupId" || key.startsWith("training:$groupId:"))
        state.prepareRecovery(token,key)
        if (key == "menu:$groupId") groupMenu(groupId)
        else trainingCard(groupId, key.removePrefix("training:$groupId:"))
    }
}
