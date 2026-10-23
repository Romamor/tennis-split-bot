package ru.movereon.tennis.selfservice

import ru.movereon.tennis.telegram.*
import java.time.Clock

/** Ordinary Telegram messages share token protection and uncertain-send recovery. */
internal class MessageDelivery(private val api:TelegramApi,private val state:InteractionStore,private val clock:Clock) {
    private val noticeDeletionRetries=object:LinkedHashMap<String,Long>() {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Long>?)=size>256
    }
    fun send(key: String, group: Long, chat: Long, user: Long?, out: Screens.Output, scope: String, recreateMissing:Boolean=true): Boolean {
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
            return true
        } catch (failure: TelegramFailure) {
            state.deliveryResult(key, when (failure.kind) { FailureKind.UNCERTAIN -> "UNKNOWN"; FailureKind.RETRY_LATER -> "RETRY"; else -> "FAILED" })
            if (failure.kind != FailureKind.UNCERTAIN) state.replace(scope, oldTokens)
            if (failure.kind == FailureKind.UNCERTAIN && old?.message == null) return false
            throw failure
        }
    }
    fun retirePrivateMenus(user:Long?=null) {
        state.retiredPrivateMenus(user).forEach { old ->
            try {
                remove(old,"Меню обновлено. Используй последнее сообщение бота.",input=old.key.startsWith("input:"))
                state.forgetDelivery(old.key)
            } catch(f:TelegramFailure) {
                if(f.kind==FailureKind.MESSAGE_MISSING || f.kind==FailureKind.NOT_MODIFIED) state.forgetDelivery(old.key)
                else if(f.kind==FailureKind.REJECTED) state.deliveryResult(old.key,"BLOCKED")
                if(f.kind==FailureKind.RETRY_LATER || f.code in setOf(401,409)) throw f
            }
        }
    }
    fun removePaymentNotices() {
        state.pendingNoticeRemovals().filter { (noticeDeletionRetries[it.key] ?: 0)<=clock.millis() }.take(2).forEach { old ->
            try {
                remove(old,"Уведомление закрыто. Актуальные переводы — в «Мои финансы».")
                state.noticeRemoved(old);noticeDeletionRetries.remove(old.key)
            } catch(f:TelegramFailure) {
                if(f.kind in setOf(FailureKind.MESSAGE_MISSING,FailureKind.NOT_MODIFIED)) state.noticeRemoved(old)
                else {
                    noticeDeletionRetries[old.key]=clock.millis()+(f.retryAfter ?: 60).coerceIn(1,900)*1000L
                    if(f.kind==FailureKind.REJECTED) state.deliveryResult(old.key,"BLOCKED")
                }
                if(f.kind==FailureKind.RETRY_LATER || f.code in setOf(401,409)) throw f
            }
        }
    }
    private fun remove(old:Delivery,text:String,input:Boolean=false) {
        try { api.delete(old.chat,requireNotNull(old.message)) }
        catch(f:TelegramFailure) {
            if(f.kind==FailureKind.MESSAGE_MISSING) return
            if(f.kind!=FailureKind.REJECTED || f.code!=400) throw f
            if(input) return
            // Past Telegram's deletion window, remove the obsolete controls instead.
            api.editRich(old.chat,requireNotNull(old.message),text,"<p>$text</p>",TgKeyboard(emptyList()))
        }
    }
}
