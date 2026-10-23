package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

/** Resolve an obsolete edit to a readable card, without retrying its mutation or changing the warning. */
internal class InputRecovery(private val service:SettlementService,private val access:(Long,Long)->Access) {
    fun resolve(plan:EventPlan):EventPlan {
        if(plan.command!=null || plan.defaultUpdate!=null || plan.screen.kind !in setOf("form","add_players")) return plan
        val f=plan.form ?: return if(plan.screen.kind=="form") plan.copy(screen=ScreenAction("menu",0)) else plan
        fun redirect(screen:ScreenAction,notice:String)=plan.copy(screen=screen,form=null,notice=notice)
        if(f.kind=="payment_edit") {
            val a=access(f.group,plan.user)
            val t=try { service.transfer(a,f.transfer) } catch(e:AccountingException) {
                if(e.code!=ErrorCode.INVALID_INPUT) throw e
                return redirect(ScreenAction("finance",f.group),"Перевод больше недоступен. Ввод закрыт.")
            }
            if(t.version!=f.version || !canEditPayment(a,t,service.isAdmin(a)))
                return redirect(ScreenAction("finance_payment",f.group,f.transfer,back=f.origin?.back),"Перевод изменился. Открой редактирование заново.")
        } else if(f.training.isNotEmpty() && f.kind in setOf("title","date","time","ready","paid","add_players","pick_players","pick_account","pick_add_player")) {
            val a=access(f.group,plan.user)
            val t=try { service.training(a,f.training) } catch(e:AccountingException) {
                if(e.code!=ErrorCode.INVALID_INPUT) throw e
                return redirect(ScreenAction("my_trainings",0),"Тренировка больше недоступна. Ввод не применён.")
            }
            val details=f.kind in setOf("title","date","time","ready")
            val permitted=if(f.kind=="paid") f.user==plan.user || service.canEdit(a,t) else service.canEdit(a,t)
            if(t.phase!=TrainingPhase.OPEN || !permitted || details && t.version!=f.version)
                return redirect(ScreenAction("my_training",f.group,f.training),"Тренировка изменилась. Ввод не применён; открой её карточку.")
        } else if(f.kind in setOf("default_title","default_time")) {
            val defaults=service.trainingDefaults(plan.user)
            val current=if(f.kind=="default_title") defaults.title else defaults.time
            if(f.defaultValue!=current) return redirect(ScreenAction("training_settings",0),"Настройка уже изменилась. Открой её заново.")
        }
        return if(plan.screen.kind=="form") plan.copy(screen=ScreenAction("form",f.group,f.training,back=f.origin)) else plan
    }
}
