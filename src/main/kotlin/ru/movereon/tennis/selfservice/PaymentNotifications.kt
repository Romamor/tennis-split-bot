package ru.movereon.tennis.selfservice

import java.time.Clock
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.sqlQuery
import ru.movereon.tennis.telegram.*

/** One recipient-owned message per new pending transfer. No bank or ledger calls on delivery. */
internal class PaymentNotifications(
    private val service:SettlementService,private val state:InteractionStore,private val clock:Clock,
    private val send:(String,Long,Long,Screens.Output)->Boolean,
) {
    private var lastExpiryScan=Long.MIN_VALUE
    private val retries=object:LinkedHashMap<Pair<Long,String>,Long>() {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Long,String>,Long>?)=size>256
    }
    fun maintain() {
        val now=clock.instant().epochSecond
        if(lastExpiryScan==Long.MIN_VALUE || now-lastExpiryScan>=60) {
            state.expiredPaymentNotices().forEach { state.removePaymentNotice(it) }
            lastExpiryScan=now
        }
        val work=state.pendingPaymentNotices().firstOrNull { (retries[it.group to it.transfer] ?: 0)<=clock.millis() } ?: return
        val key="payment-notice:${work.group}:${work.transfer}"
        val old=state.delivery(key)
        // An edit of a historic transfer never creates a new notification.
        if(old==null && !work.created || old?.status=="BLOCKED") {
            state.paymentNoticeDelivered(work);return
        }
        val recipient=service.database.read { c -> sqlQuery(c,"SELECT to_user FROM transfers WHERE group_id=? AND id=?",work.group,work.transfer) { it.getLong(1) }.single() }
        val transfer=service.transfer(Access(work.group,recipient),work.transfer)
        if(transfer.status!=PaymentStatus.REVIEW) {
            state.removePaymentNotice(key);state.paymentNoticeDelivered(work);return
        }
        if(old?.status=="UNKNOWN" && old.message==null) { state.paymentNoticeDelivered(work);return }
        if(old?.message==null && (transfer.status!=PaymentStatus.REVIEW || !state.knowsPrivateChat(recipient))) {
            state.paymentNoticeDelivered(work);return
        }
        val sender=service.account(transfer.from)
        val name=Screens.clean(sender.name,36)
        val group=Screens.clean(service.group(work.group).title,100)
        val heading="Новый перевод"
        val prompt="Подтверди получение, если деньги пришли."
        val text="$heading\n$name отметил перевод тебе: ${transfer.amount} ₽.\nГруппа: $group\n$prompt".trimEnd()
        val html="<h3>$heading</h3><p>${TrainingCard.profileLink(sender.id,name,sender.username)} отметил перевод тебе: <b>${transfer.amount} ₽</b>.<br>Группа: ${TrainingCard.escape(group)}</p>"+"<p>$prompt</p>"
        val tokens=state.buttonBatch {
            setOf(state.button(ScreenAction("finance_receive_confirm",work.group,transfer.id,back=ScreenAction("finance_receive",work.group)),recipient,key))
        }
        val keyboard=TgKeyboard(listOf(listOf(TgButton("📥 Подтвердить получение",callbackData="n:${tokens.single()}",style="primary"))))
        val out=Screens.Output(text,keyboard,tokens,richHtml=html)
        try {
            send(key,work.group,recipient,out)
            if(old?.message==null) state.startPaymentNoticeLifetime(key)
            state.paymentNoticeDelivered(work);retries.remove(work.group to work.transfer)
        } catch(f:TelegramFailure) {
            if(f.code in setOf(401,409)) throw f
            if(f.kind==FailureKind.NOT_MODIFIED) {
                state.deliveryResult(key,"SENT",old?.message);state.replace(key,out.tokens)
                state.paymentNoticeDelivered(work);retries.remove(work.group to work.transfer);return
            }
            if(f.kind in setOf(FailureKind.RETRY_LATER,FailureKind.UNCERTAIN)) {
                retries[work.group to work.transfer]=clock.millis()+(f.retryAfter ?: 5).coerceIn(1,60)*1000L
                throw f
            }
            // Blocked/never-started chats and deleted notices do not block other recipients.
            state.deliveryResult(key,"BLOCKED");state.replace(key,emptySet());state.paymentNoticeDelivered(work)
        }
    }
}
