package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.telegram.*

/** Telegram delivery is separated from the transactional poll/sign-up model. */
internal class PollWorkflow(private val polls:TrainingPolls,private val state:InteractionStore,private val api:TelegramApi) {
    fun publish(p:TrainingPoll) {
        if(p.status !in setOf("PENDING","FAILED")) return
        val key="poll:${p.group}:${p.id}"
        val token=state.button(ScreenAction("poll_close_confirm",p.group,p.id),null,key,permanent=true)
        val keyboard=TgKeyboard(listOf(listOf(TgButton("🏁 Завершить сбор",callbackData="n:$token",style="primary"))))
        polls.status(p,"SENDING")
        try {
            val sent=api.sendPoll(p.group,"🏓 ${p.title}\n${Screens.date(p.date)} · начало ${p.time}\nКогда придёшь?",p.options(),keyboard)
            polls.attach(p,requireNotNull(sent.poll).id,sent.id)
            recordDelivery(polls.get(p.group,p.id))
        } catch(f:TelegramFailure) {
            polls.status(p,when(f.kind) { FailureKind.UNCERTAIN -> "UNKNOWN";FailureKind.RETRY_LATER -> "PENDING";else -> "FAILED" })
            if(f.kind==FailureKind.RETRY_LATER) throw f
        }
    }
    fun recordDelivery(p:TrainingPoll) {
        val key="poll:${p.group}:${p.id}"
        if(state.delivery(key)?.message==p.message) return
        state.sending(key,p.group,p.group,null)
        state.deliveryResult(key,"SENT",requireNotNull(p.message))
        state.pinStatus(key,if(p.status=="CLOSED") "UNPIN_PENDING" else "PENDING")
    }
    fun stop(p:TrainingPoll) {
        if(p.status!="CLOSING" || p.stopped) return
        api.stopPoll(p.group,requireNotNull(p.message))
        // stopPoll can succeed before a crash; both it and keyboard removal are retry-safe.
        api.editKeyboard(p.group,p.message,TgKeyboard(emptyList()))
        polls.stopped(p)
    }
}
