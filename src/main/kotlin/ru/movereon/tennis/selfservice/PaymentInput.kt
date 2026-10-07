package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import java.util.UUID

/** One draft for calculated, balance-based and freely entered transfers. No ledger writes before save. */
internal class PaymentInput(private val service:SettlementService,private val state:InteractionStore) {
    fun prepare(user:Long,chat:Long,a:Access,action:ScreenAction,saved:InputForm?):EventPlan {
        fun show(f:InputForm)=EventPlan(user,chat,ScreenAction("form",a.groupId),form=f)
        fun leave(destination:ScreenAction=ScreenAction("finance",a.groupId))=EventPlan(user,chat,destination)
        if(action.kind in setOf("payment_new","finance_send_confirm")) {
            val admin=action.option=="admin"
            if(isForm(saved)) {
                checkAccounting(saved!!.group==a.groupId && saved.adminPayment==admin,ErrorCode.INVALID_STATE,"Сначала заверши или отмени открытый ввод перевода")
                return show(saved)
            }
            checkAccounting(!admin || service.isAdmin(a),ErrorCode.FORBIDDEN,"Доступно администратору этой группы")
            val origin=action.back ?: ScreenAction("finance",a.groupId)
            if(action.user>0) {
                service.groupAccount(a,action.user)
                require(action.user!=user) { "Выбери другого участника" }
                val calculated=action.kind=="finance_send_confirm"
                val context=if(calculated) service.paymentDraftContext(a,user,action.user) else null
                if(calculated) checkAccounting(context!!.recommendation==action.value,ErrorCode.INVALID_STATE,"Сумма изменилась. Обнови предложения переводов")
                return show(InputForm("payment_ready",a.groupId,user=action.user,paymentFrom=user,amount=action.value,
                    roundingAvailable=calculated || action.value>0,origin=origin))
            }
            return show(InputForm(if(admin) "payment_from" else "payment_to",a.groupId,
                paymentFrom=if(admin) 0 else user,adminPayment=admin,origin=origin))
        }
        val f=requireNotNull(saved) { "Открой ввод перевода заново" }
        checkAccounting(isForm(f) && f.group==a.groupId && state.formSignature(f)==action.option,
            ErrorCode.STALE_VERSION,"Ввод изменился. Используй текущие кнопки перевода")
        if(action.kind=="payment_cancel") return leave()
        checkAccounting(!f.adminPayment || service.isAdmin(a),ErrorCode.FORBIDDEN,"Доступно администратору этой группы")
        checkAccounting(f.adminPayment || f.paymentFrom==user,ErrorCode.FORBIDDEN,"Можно записать только свою отправку")
        return when(action.kind) {
            "payment_page" -> { require(f.kind in setOf("payment_from","payment_to"));show(f.copy(page=action.page)) }
            "payment_pick" -> {
                require(f.kind in setOf("payment_from","payment_to"));service.groupAccount(a,action.user)
                if(f.kind=="payment_from") show(f.copy(kind="payment_to",paymentFrom=action.user,user=0,page=0))
                else {
                    require(action.user!=f.paymentFrom) { "Выбери другого участника" }
                    show(f.copy(kind="payment_ready",user=action.user,amount=action.value,page=0,recipientPage=f.page,
                        recipientOrigin=ScreenAction("form",a.groupId),roundingAvailable=action.value>0,waitingForAmount=false,rounded=false))
                }
            }
            "payment_back" -> when(f.kind) {
                "payment_from" -> leave()
                "payment_to" -> if(f.adminPayment) show(f.copy(kind="payment_from",page=0)) else leave(f.origin ?: ScreenAction("finance",a.groupId))
                "payment_duplicate" -> show(f.copy(kind="payment_ready",similar=emptyList()))
                "payment_edit" -> leave(f.origin ?: ScreenAction("finance_payment",a.groupId,f.transfer))
                else -> if(f.recipientOrigin!=null) show(f.copy(kind="payment_to",page=f.recipientPage,waitingForAmount=false))
                    else leave(f.origin ?: ScreenAction("finance",a.groupId))
            }
            "payment_adjust","payment_balance","payment_recommend","payment_round","payment_type" -> {
                require(f.kind=="payment_ready")
                val updated=when(action.kind) {
                    "payment_type" -> f.copy(waitingForAmount=true)
                    "payment_adjust" -> f.copy(amount=(f.amount.toBigInteger()+action.value.toBigInteger()).max(java.math.BigInteger.ZERO).toAmount(),waitingForAmount=false)
                    "payment_balance" -> {
                        require(action.user in setOf(f.paymentFrom,f.user))
                        val balance=service.balances(a)[action.user] ?: 0L
                        require(balance!=0L) { "Баланс равен нулю" }
                        f.copy(amount=balance.toBigInteger().abs().toAmount(),roundingAvailable=true,rounded=false,waitingForAmount=false)
                    }
                    "payment_recommend" -> {
                        val amount=service.paymentDraftContext(a,f.paymentFrom,f.user).recommendation
                            ?: throw AccountingException(ErrorCode.INVALID_STATE,"Для этой пары сейчас нет рекомендуемого перевода")
                        f.copy(amount=amount,roundingAvailable=true,rounded=false,waitingForAmount=false)
                    }
                    else -> {
                        require(f.roundingAvailable && !f.rounded && f.amount>0) { "Округление недоступно" }
                        f.copy(amount=roundHundred(f.amount),rounded=true,waitingForAmount=false)
                    }
                }
                show(updated)
            }
            "payment_save" -> {
                require(f.kind in setOf("payment_ready","payment_duplicate") && f.amount>0) { "Сначала укажи положительную сумму" }
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
        if(f.adminPayment && !service.isAdmin(a)) return EventPlan(user,chat,ScreenAction("finance",a.groupId),notice="Права администратора изменились. Ввод перевода закрыт.")
        if(f.kind=="payment_edit") {
            val command=SettlementCommand.EditPaymentAmount(f.transfer,f.version,readAmount(value))
            return EventPlan(user,chat,f.origin ?: ScreenAction("finance_payment",a.groupId,f.transfer),command)
        }
        require(f.kind in setOf("payment_amount","payment_ready") && (f.kind=="payment_amount" || f.waitingForAmount)) { "Нажми «Ввести сумму сообщением»" }
        return EventPlan(user,chat,ScreenAction("form",a.groupId),form=f.copy(kind="payment_ready",amount=readAmount(value),waitingForAmount=false))
    }
    companion object {
        private fun readAmount(value:String):Long {
            require(value.trim().matches(Regex("[0-9]+"))) { "Напиши положительную сумму в рублях, без копеек" }
            val amount=parseAmount(value)
            return amount
        }
        fun roundHundred(amount:Long):Long=(amount.toBigInteger()+java.math.BigInteger.valueOf(50)).divide(java.math.BigInteger.valueOf(100)).multiply(java.math.BigInteger.valueOf(100)).toAmount()
        val actions=setOf("payment_new","finance_send_confirm","payment_pick","payment_page","payment_back","payment_cancel","payment_save","payment_previous","payment_adjust","payment_balance","payment_recommend","payment_round","payment_type")
        val forms=setOf("payment_from","payment_to","payment_amount","payment_ready","payment_duplicate","payment_edit")
        fun isForm(f:InputForm?)=f?.kind in forms
    }
}
