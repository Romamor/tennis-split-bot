package ru.movereon.tennis.selfservice

import ru.movereon.tennis.telegram.*
import java.time.Clock

/** Ordinary Telegram messages share token protection and uncertain-send recovery. */
internal class MessageDelivery(private val api:TelegramApi,private val state:InteractionStore,private val clock:Clock) {
    fun send(key: String, group: Long, chat: Long, user: Long?, out: Screens.Output, scope: String, recreateMissing:Boolean=true): Boolean {
        val delay=state.deliveryDelay(key)
        if(delay>0) throw TelegramFailure(FailureKind.RETRY_LATER,retryAfter=((delay+999)/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val old = state.delivery(key)
        if(!recreateMissing && old?.message==null) return true
        if (old?.status == "UNKNOWN" && old.message == null) return false
        val oldTokens = state.activeTokens(scope)
        state.protect(out.tokens)
        state.sending(key, group, chat, user)
        try {
            if (old?.message != null) {
                try {
                    if(out.richHtml!=null) api.editRich(chat,old.message,out.text,out.richHtml,out.keyboard,out.photoId)
                    else api.edit(chat, old.message, out.text, out.keyboard)
                }
                catch (failure: TelegramFailure) {
                    if (failure.kind != FailureKind.MESSAGE_MISSING) throw failure
                    state.forgetDelivery(key)
                    if(!recreateMissing) { state.replace(scope,emptySet());return true }
                    return send(key, group, chat, user, out, scope)
                }
                state.deliveryResult(key, "SENT", old.message)
            } else {
                val msg = if(out.richHtml!=null) api.sendRich(chat,out.text,out.richHtml,out.keyboard,out.photoId)
                    else api.send(chat, out.text, out.keyboard)
                state.deliveryResult(key, "SENT", msg.id)
            }
            state.replace(scope, out.tokens)
            state.clearDeliveryRetry(key)
            return true
        } catch (failure: TelegramFailure) {
            state.deliveryResult(key, when (failure.kind) { FailureKind.UNCERTAIN -> "UNKNOWN"; FailureKind.RETRY_LATER -> "RETRY"; else -> "FAILED" })
            if(failure.kind==FailureKind.RETRY_LATER) state.retryDelivery(key,failure)
            if (failure.kind != FailureKind.UNCERTAIN) state.replace(scope, oldTokens)
            if (failure.code in setOf(401,409) || failure.backgroundDeferred) throw failure
            if (failure.kind == FailureKind.UNCERTAIN && old?.message == null) return false
            throw failure
        }
    }
    fun retirePrivateMenus(user:Long?=null) {
        state.retiredPrivateMenus(user).take(2).forEach { old ->
            try {
                remove(old,"Меню обновлено. Используй последнее сообщение бота.",input=old.key.startsWith("input:"))
                state.forgetDelivery(old.key)
            } catch(f:TelegramFailure) {
                if(f.code in setOf(401,409) || f.backgroundDeferred) throw f
                when(f.kind) {
                    FailureKind.MESSAGE_MISSING,FailureKind.NOT_MODIFIED -> { state.forgetDelivery(old.key) }
                    FailureKind.REJECTED -> { state.deliveryResult(old.key,"BLOCKED") }
                    else -> deferDeletion(old,f)
                }
            }
        }
    }
    fun removePaymentNotices() {
        state.pendingNoticeRemovals().take(2).forEach { old ->
            try {
                remove(old,"Уведомление закрыто. Актуальные переводы — в «Мои финансы».")
                state.noticeRemoved(old)
            } catch(f:TelegramFailure) {
                if(f.code in setOf(401,409) || f.backgroundDeferred) throw f
                if(f.kind in setOf(FailureKind.MESSAGE_MISSING,FailureKind.NOT_MODIFIED)) { state.noticeRemoved(old) }
                else {
                    deferDeletion(old,f)
                    if(f.kind==FailureKind.REJECTED) state.deliveryResult(old.key,"BLOCKED")
                }
            }
        }
    }
    private fun deferDeletion(old:Delivery,failure:TelegramFailure) = state.retryDelivery(old.key,failure,defaultSeconds=60)
    private fun remove(old:Delivery,text:String,input:Boolean=false) {
        try { api.delete(old.chat,requireNotNull(old.message)) }
        catch(f:TelegramFailure) {
            if(f.code in setOf(401,409) || f.backgroundDeferred) throw f
            if(f.kind==FailureKind.MESSAGE_MISSING) return
            if(f.kind!=FailureKind.REJECTED || f.code!=400) throw f
            if(input) return
            // Past Telegram's deletion window, remove the obsolete controls instead.
            api.editRich(old.chat,requireNotNull(old.message),text,"<p>$text</p>",TgKeyboard(emptyList()))
        }
    }
}
