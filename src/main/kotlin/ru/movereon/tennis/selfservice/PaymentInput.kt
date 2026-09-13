package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import java.util.UUID

/** Personal and administrative payment input share navigation, never posting permissions. */
internal class PaymentInput(private val service:SettlementService,private val state:InteractionStore) {
    fun prepare(user:Long,chat:Long,a:Access,action:ScreenAction,saved:InputForm?):EventPlan {
        fun show(f:InputForm)=EventPlan(user,chat,ScreenAction("form",a.groupId),form=f)
        fun finance()=EventPlan(user,chat,ScreenAction("finance",a.groupId))
        if(action.kind=="payment_new") {
            val admin=action.option=="admin"
            if(isForm(saved)) {
                checkAccounting(saved!!.group==a.groupId && saved.adminPayment==admin,ErrorCode.INVALID_STATE,"Сначала заверши или отмени открытый ввод платежа")
                return show(saved)
            }
            checkAccounting(!admin || service.isAdmin(a),ErrorCode.FORBIDDEN,"Доступно администратору этой группы")
            return show(InputForm(if(admin) "payment_from" else "payment_to",a.groupId,
                paymentFrom=if(admin) 0 else user,adminPayment=admin,origin=ScreenAction("finance",a.groupId)))
        }
        val f=requireNotNull(saved) { "Открой ввод платежа заново" }
        checkAccounting(isForm(f) && f.group==a.groupId && state.formSignature(f)==action.option,
            ErrorCode.STALE_VERSION,"Ввод изменился. Используй текущие кнопки платежа")
        if(action.kind=="payment_cancel") return finance()
        checkAccounting(!f.adminPayment || service.isAdmin(a),ErrorCode.FORBIDDEN,"Доступно администратору этой группы")
        checkAccounting(f.adminPayment || f.paymentFrom==user,ErrorCode.FORBIDDEN,"Можно записать только свою отправку")
        return when(action.kind) {
            "payment_page" -> { require(f.kind in setOf("payment_from","payment_to"));show(f.copy(page=action.page)) }
            "payment_pick" -> {
                require(f.kind in setOf("payment_from","payment_to"))
                service.groupAccount(a,action.user)
                if(f.kind=="payment_from") show(f.copy(kind="payment_to",paymentFrom=action.user,user=0,page=0))
                else {
                    require(action.user!=f.paymentFrom) { "Выбери другого участника" }
                    show(f.copy(kind="payment_amount",user=action.user,page=0))
                }
            }
            "payment_back" -> when(f.kind) {
                "payment_from" -> finance()
                "payment_to" -> if(f.adminPayment) show(f.copy(kind="payment_from",page=0)) else finance()
                else -> show(f.copy(kind=when(f.kind) { "payment_amount"->"payment_to";"payment_ready"->"payment_amount";"payment_duplicate"->"payment_ready";else->error("Unknown payment step") },page=0))
            }
            "payment_save" -> {
                require(f.kind in setOf("payment_ready","payment_duplicate")) { "Сначала укажи сумму" }
                val id=UUID.randomUUID().toString()
                val command=if(f.adminPayment) SettlementCommand.RecordAdminPayment(id,f.paymentFrom,f.user,f.amount)
                    else SettlementCommand.SendOtherPayment(id,f.user,f.amount,f.kind=="payment_duplicate")
                EventPlan(user,chat,ScreenAction("finance_payment",a.groupId,id,option="saved"),command,form=f)
            }
            "payment_previous" -> {
                require(f.kind=="payment_duplicate" && action.id in f.similar)
                EventPlan(user,chat,ScreenAction("finance_payment",a.groupId,action.id,back=ScreenAction("form",a.groupId)),form=f)
            }
            else -> error("Unknown payment input action")
        }
    }
    fun text(user:Long,chat:Long,a:Access,f:InputForm,value:String):EventPlan {
        if(f.adminPayment && !service.isAdmin(a)) return EventPlan(user,chat,ScreenAction("finance",a.groupId),notice="Права администратора изменились. Ввод платежа закрыт.")
        val updated=if(f.kind=="payment_amount") f.copy(kind="payment_ready",amount=parseAmount(value)) else f
        return EventPlan(user,chat,ScreenAction("form",a.groupId),form=updated)
    }
    companion object {
        val actions=setOf("payment_new","payment_pick","payment_page","payment_back","payment_cancel","payment_save","payment_previous")
        val forms=setOf("payment_from","payment_to","payment_amount","payment_ready","payment_duplicate")
        fun isForm(f:InputForm?)=f?.kind in forms
    }
}
