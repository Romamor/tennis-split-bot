package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.telegram.*
import ru.movereon.tennis.core.*

/** Telegram delivery is separated from the transactional poll/sign-up model. */
internal class PollWorkflow(private val polls:TrainingPolls,private val state:InteractionStore,private val api:TelegramApi) {
    fun execute(plan:EventPlan,a:Access?,requirePublication:(Long,Long)->Unit):EventPlan {
        var effective=plan
            if(effective.screen.kind=="poll_publish") {
                val f=requireNotNull(effective.form)
                requirePublication(effective.screen.group,effective.user)
                val p=polls.create(requireNotNull(a),f.pollId,f.title,f.date,f.time,f.declineLabel,f.pollPhotoId)
                if(p.status in setOf("PENDING","FAILED")) checkAccounting(polls.enabled(p.group),ErrorCode.FORBIDDEN,"Сбор через опрос выключен в этой группе")
                publish(p)
                effective=effective.copy(screen=ScreenAction("poll_detail",p.group,p.id),form=null)
            }
            if(effective.screen.kind=="poll_close") {
                polls.beginClose(requireNotNull(a),effective.screen.id)
                stop(polls.get(a.groupId,effective.screen.id))
                effective=effective.copy(screen=effective.screen.copy(kind="poll_detail"))
            }
            if(effective.screen.kind=="poll_retry") {
                val p=polls.get(requireNotNull(a).groupId,effective.screen.id)
                polls.requireManager(a,p);requirePublication(a.groupId,effective.user)
                if(p.status in setOf("PENDING","FAILED")) checkAccounting(polls.enabled(p.group),ErrorCode.FORBIDDEN,"Сбор через опрос выключен в этой группе")
                publish(p)
                effective=effective.copy(screen=effective.screen.copy(kind="poll_detail"))
            }
            if(effective.screen.kind=="poll_discard") {
                val p=polls.get(requireNotNull(a).groupId,effective.screen.id)
                polls.requireManager(a,p)
                checkAccounting(p.status in setOf("UNKNOWN","FAILED","PENDING"),ErrorCode.INVALID_STATE,"Опубликованный опрос нужно завершить")
                if(p.message!=null) {
                    api.stopPoll(p.group,p.message)
                    api.editKeyboard(p.group,p.message,TgKeyboard(emptyList()))
                    api.unpin(p.group,p.message)
                }
                polls.discard(a,p.id)
                state.forgetDelivery("poll:${p.group}:${p.id}")
                effective=effective.copy(screen=ScreenAction(if(effective.chat<0) "close_panel" else "menu",if(effective.chat<0) a.groupId else 0))
            }
        return effective
    }
    fun publish(p:TrainingPoll) {
        if(p.status !in setOf("PENDING","FAILED")) return
        val key="poll:${p.group}:${p.id}"
        val token=state.button(ScreenAction("poll_close_confirm",p.group,p.id),null,key,permanent=true)
        val keyboard=TgKeyboard(listOf(listOf(TgButton("🏁 Завершить сбор",callbackData="n:$token",style="primary"))))
        polls.status(p,"SENDING")
        try {
            val sent=api.sendPoll(p.group,"🏓 ${p.title}\n${Screens.date(p.date)} · начало ${p.time}",p.options(),keyboard,p.photoId)
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
