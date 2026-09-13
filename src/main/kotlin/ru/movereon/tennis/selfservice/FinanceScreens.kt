package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** The issue #3 payment flow. Financial state changes happen only in service commands. */
internal class FinanceScreens(private val service:SettlementService) {
    fun render(layout:ScreenLayout,access:Access?,account:(Long)->Account,personRow:(Long,String,ScreenAction)->Unit):ScreenContent = with(layout) {
        val a=requireNotNull(access)
        fun person(id:Long)=clean(account(id).name,36)
        fun footer() { rows+=listOf(button("⬅️ Назад",ScreenAction("finance",a.groupId)),button("Меню",ScreenAction("menu",0))) }
        when(action.kind) {
            "finance" -> {
                rows+=listOf(button("Отправить платеж",next("finance_send")),button("Принять платеж",next("finance_receive")))
                row("История платежей",next("finance_history"));row("Должники",next("finance_debtors"))
                row("Назад",ScreenAction("menu",0))
                ScreenContent("Мои финансы:")
            }
            "finance_send" -> {
                val p=service.paymentSuggestions(a,page=action.page)
                p.items.forEach { personRow(it.to.value.toLong(),"${person(it.to.value.toLong())} · ${it.amount} ₽",next("finance_send_confirm",target=it.to.value.toLong(),value=it.amount).copy(back=action.copy(page=p.index))) }
                pages(p.index,p.pages);footer()
                ScreenContent(if(p.total==0) "Нет доступных платежей" else "Выберите доступный платеж:")
            }
            "finance_send_confirm" -> {
                val available=service.paymentSuggestions(a,page=action.back?.page ?: 0).items
                checkAccounting(available.any { it.to.value==action.user.toString() && it.amount==action.value },ErrorCode.INVALID_STATE,"Платёж больше недоступен или сумма изменилась. Обнови список платежей")
                row("Платеж отправлен · ${action.value} ₽",next("finance_send_save",value=action.value))
                rows+=listOf(button("⬅️ Назад",action.back ?: next("finance_send")),button("Меню",ScreenAction("menu",0)))
                ScreenContent("Отправь ${person(action.user)} ${action.value} ₽ и нажми кнопку «Платеж отправлен · ${action.value} ₽».")
            }
            "finance_receive" -> {
                val p=service.financePayments(a,action.page,incomingOnly=true)
                p.items.forEach { personRow(it.from,"${person(it.from)} · ${it.amount} ₽ · ${date(it.date)}",next("finance_receive_save",id=it.id).copy(back=action.copy(page=p.index))) }
                pages(p.index,p.pages);footer()
                ScreenContent(if(p.total==0) "Нет доступных платежей" else "Принять платеж:")
            }
            "finance_debtors" -> {
                val p=service.paymentSuggestions(a,incoming=true,page=action.page)
                pages(p.index,p.pages);row("⬅️ Назад",ScreenAction("finance",a.groupId))
                table("Список должников:",listOf("Пользователь","Сумма"),p.items.map { listOf(person(it.from.value.toLong()),"${it.amount} ₽") })
            }
            "finance_history" -> {
                val p=service.financePayments(a,action.page)
                pages(p.index,p.pages);row("⬅️ Назад",ScreenAction("finance",a.groupId))
                table("История платежей",listOf("От кого","Кому","Сумма","Дата","Статус"),p.items.map {
                    listOf(person(it.from),person(it.to),"${it.amount} ₽",date(it.date),if(it.status==PaymentStatus.ACTIVE) "Выполнен" else "В процессе")
                })
            }
            else -> error("Unknown finance screen")
        }
    }
    private fun table(title:String,headers:List<String>,values:List<List<String>>):ScreenContent {
        val plain=title+if(values.isEmpty()) "\nСписок пуст." else "\n"+(listOf(headers)+values).joinToString("\n") { it.joinToString(" | ") }
        val html="<h3>${TrainingCard.escape(title)}</h3>"+if(values.isEmpty()) "<p>Список пуст.</p>" else RichTable.OPEN+"<tr>"+headers.joinToString("") { "<th>${TrainingCard.escape(it)}</th>" }+"</tr>"+values.joinToString("") { row -> "<tr>"+row.mapIndexed { index,value -> "<td${if(headers[index]=="Сумма") " align=\"right\"" else ""}>${TrainingCard.escape(value)}</td>" }.joinToString("")+"</tr>" }+"</table>"
        return ScreenContent(plain,html)
    }
    companion object {
        val kinds=setOf("finance","finance_send","finance_send_confirm","finance_receive","finance_debtors","finance_history")
        val retiredActions=setOf("debts","balances","settled","transfers","transfer","transfer_people","transfer_direction","transfer_amount","suggested_transfer","save_transfer","transfer_date","transfer_note","edit_transfer_amount","save_transfer_amount","review_transfer","confirm_transfer","cancel_transfer","transfer_history")
        fun retiredForm(form:InputForm?)=form?.kind?.let { it.startsWith("transfer_") || it.startsWith("edit_transfer_") }==true
        fun retiredCommand(command:SettlementCommand?)=command is SettlementCommand.RecordTransfer || command is SettlementCommand.ChangeTransfer || command is SettlementCommand.EditTransferAmount
    }
}
