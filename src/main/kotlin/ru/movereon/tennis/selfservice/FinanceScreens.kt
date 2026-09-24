package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** Finance menus render posted balances and receipts; commands alone change financial state. */
internal class FinanceScreens(private val service:SettlementService) {
    fun render(layout:ScreenLayout,access:Access?,account:(Long)->Account,personRow:(Long,String,ScreenAction)->Unit):ScreenContent = with(layout) {
        val a=requireNotNull(access)
        fun person(id:Long)=clean(account(id).name,36)
        fun footer() { rows+=listOf(button("⬅️ Назад",ScreenAction("finance",a.groupId)),button("Меню",ScreenAction("menu",0))) }
        when(action.kind) {
            "finance" -> {
                val summary=service.financeSummary(a)
                rows+=listOf(button("Отправить платеж",next("finance_send")),button("Принять платеж(${summary.pendingReceiveCount})",next("finance_receive")))
                rows+=listOf(button("Другой платёж",next("payment_new")),button("История платежей",next("finance_history")))
                row("Баланс группы",next("finance_balances"))
                if(service.isAdmin(a)) row("Записать платёж за участников",next("payment_new",option="admin"))
                row("Назад",ScreenAction("menu",0))
                val balance=when {
                    summary.balance>0 -> "+${summary.balance} ₽ — тебе осталось получить"
                    summary.balance<0 -> "−${summary.balance.toBigInteger().negate()} ₽ — тебе осталось внести"
                    else -> "0 ₽"
                }
                ScreenContent(buildString {
                    append("Мои финансы:\nБаланс: $balance")
                    if(summary.pendingSentCount>0) append("\nОтправлено, ждёт подтверждения: ${summary.pendingSentCount} · ${summary.pendingSentAmount} ₽")
                    if(summary.pendingReceiveCount>0) append("\nТебе подтвердить получение: ${summary.pendingReceiveCount} · ${summary.pendingReceiveAmount} ₽")
                    if(summary.pendingSentCount>0 || summary.pendingReceiveCount>0)
                        append("\nОжидающие платежи пока не меняют баланс.")
                })
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
                p.items.forEach { personRow(it.from,"${person(it.from)} · ${it.amount} ₽ · ${date(it.date)}",next("finance_receive_confirm",id=it.id).copy(back=action.copy(page=p.index))) }
                pages(p.index,p.pages);footer()
                ScreenContent(if(p.total==0) "Нет доступных платежей" else "Принять платеж:")
            }
            "finance_receive_confirm" -> {
                val t=service.transfer(a,action.id)
                checkAccounting(t.to==a.userId && t.status==PaymentStatus.REVIEW,ErrorCode.INVALID_STATE,"Этот платёж недоступен для принятия")
                row("Да, получил",next("finance_receive_save"))
                row("Назад",action.back ?: next("finance_receive"))
                ScreenContent("Деньги пришли?\nОт кого: ${person(t.from)}\nКому: ${person(t.to)}\n${t.amount} ₽")
            }
            "finance_received" -> {
                row("К моим финансам",ScreenAction("finance",a.groupId))
                ScreenContent("Платёж учтён.\nБаланс обновлён.")
            }
            "finance_balances" -> {
                val p=service.financeBalances(a,action.page)
                pages(p.index,p.pages);row("⬅️ Назад",ScreenAction("finance",a.groupId))
                val data=table("Баланс группы",listOf("Участник","Баланс"),p.items.map { listOf(person(it.account.id),"${if(it.balance>0) "+" else ""}${it.balance} ₽") })
                val note="+ — участнику осталось получить; − — участнику осталось внести. Ожидающие подтверждения платежи пока не учтены."
                ScreenContent(data.text+"\n"+note,data.html+"<p>${TrainingCard.escape(note)}</p>")
            }
            "finance_payment" -> {
                val t=service.transfer(a,action.id)
                checkAccounting(a.userId in setOf(t.from,t.to) || service.isAdmin(a),ErrorCode.FORBIDDEN,"Платёж доступен его сторонам и администратору группы")
                val admin=service.administrativePayment(a,t.id)
                row(if(action.back==null) "К моим финансам" else "Назад",action.back ?: ScreenAction("finance",a.groupId))
                ScreenContent("${if(t.status==PaymentStatus.REVIEW) "Ожидает подтверждения" else "Платёж учтён"}\n"+
                    "От кого: ${person(t.from)}\nКому: ${person(t.to)}\n${t.amount} ₽ · ${date(t.date)}"+
                    if(admin) "\nЗаписал администратор: ${person(t.createdBy)}. Подтверждение участников не требуется."
                    else if(t.status==PaymentStatus.REVIEW) "\nБаланс изменится после подтверждения получателем." else "")
            }
            "finance_history" -> {
                val p=service.financePayments(a,action.page)
                p.items.forEach { row("${date(it.date)} · ${person(it.from)} → ${person(it.to)} · ${it.amount} ₽",next("finance_payment",id=it.id).copy(back=action.copy(page=p.index))) }
                pages(p.index,p.pages);row("⬅️ Назад",ScreenAction("finance",a.groupId))
                table("История платежей",listOf("От кого","Кому","Сумма","Дата","Статус"),p.items.map {
                    listOf(person(it.from),person(it.to),"${it.amount} ₽",date(it.date),if(it.status==PaymentStatus.ACTIVE) "Выполнен" else "В процессе")
                })
            }
            else -> error("Unknown finance screen")
        }
    }
    fun renderForm(layout:ScreenLayout,a:Access,f:InputForm,signature:String,account:(Long)->Account,personRow:(Long,String,ScreenAction)->Unit):ScreenContent = with(layout) {
        fun person(id:Long)=clean(account(id).name,36)
        fun act(kind:String)=ScreenAction(kind,a.groupId,option=signature)
        fun nav(){ rows+=listOf(button("Назад",act("payment_back")),button("Отмена",act("payment_cancel"))) }
        if(f.adminPayment && !service.isAdmin(a)) {
            row("К моим финансам",act("payment_cancel"))
            return ScreenContent("Права администратора изменились. Закрой ввод платежа.")
        }
        val body=when(f.kind) {
            "payment_from","payment_to" -> {
                val all=service.groupAccounts(a).filter { f.kind=="payment_from" || it.id!=f.paymentFrom }
                    .sortedWith(compareBy<Account> { it.name.lowercase() }.thenBy { it.id })
                val index=f.page.coerceIn(0,maxOf(0,(all.size-1)/6))
                all.drop(index*6).take(6).forEach { personRow(it.id,person(it.id),act("payment_pick").copy(user=it.id)) }
                pages(index,maxOf(1,(all.size+5)/6),act("payment_page"))
                (if(f.kind=="payment_from") "От кого" else "Кому\nОт кого: ${person(f.paymentFrom)}")+if(all.isEmpty()) "\nСписок пуст." else ""
            }
            "payment_amount" -> "Сумма перевода\nОт кого: ${person(f.paymentFrom)}\nКому: ${person(f.user)}\nНапиши сумму в рублях. Например: 350."
            "payment_ready","payment_duplicate" -> {
                row(if(f.adminPayment) "Записать платёж" else if(f.kind=="payment_duplicate") "Да, это ещё один" else "Платёж отправлен",act("payment_save"))
                if(f.kind=="payment_duplicate" && f.similar.isNotEmpty()) row("Посмотреть предыдущий",act("payment_previous").copy(id=f.similar.first()))
                val duplicate=f.kind=="payment_duplicate" || f.adminPayment && service.similarPayments(a,f.paymentFrom,f.user,f.amount).isNotEmpty()
                (if(f.kind=="payment_duplicate") "Это ещё один платёж?\n" else if(f.adminPayment) "Запись администратором\n" else "Отправка платежа\n")+
                    "От кого: ${person(f.paymentFrom)}\nКому: ${person(f.user)}\n${f.amount} ₽\n"+
                    (if(f.adminPayment) "Сразу учтём в балансе. Подтверждение участников не требуется."
                    else "После перевода денег нажми «Платёж отправлен». Учтём сумму после подтверждения получателя.")+
                    if(duplicate) "\nЗа последние сутки уже есть такой же платёж." else ""
            }
            else -> error("Unknown payment form")
        }
        nav();ScreenContent(body)
    }
    private fun table(title:String,headers:List<String>,values:List<List<String>>):ScreenContent {
        val plain=title+if(values.isEmpty()) "\nСписок пуст." else "\n"+(listOf(headers)+values).joinToString("\n") { it.joinToString(" | ") }
        val html="<h3>${TrainingCard.escape(title)}</h3>"+if(values.isEmpty()) "<p>Список пуст.</p>" else RichTable.OPEN+"<tr>"+headers.joinToString("") { "<th>${TrainingCard.escape(it)}</th>" }+"</tr>"+values.joinToString("") { row -> "<tr>"+row.mapIndexed { index,value -> "<td${if(headers[index] in setOf("Сумма","Баланс")) " align=\"right\"" else ""}>${TrainingCard.escape(value)}</td>" }.joinToString("")+"</tr>" }+"</table>"
        return ScreenContent(plain,html)
    }
    companion object {
        val kinds=setOf("finance","finance_send","finance_send_confirm","finance_receive","finance_receive_confirm","finance_received","finance_balances","finance_payment","finance_history")
        val retiredActions=setOf("finance_debtors","debts","balances","settled","transfers","transfer","transfer_people","transfer_direction","transfer_amount","suggested_transfer","save_transfer","transfer_date","transfer_note","edit_transfer_amount","save_transfer_amount","review_transfer","confirm_transfer","cancel_transfer","transfer_history")
        fun retiredForm(form:InputForm?)=form?.kind?.let { it.startsWith("transfer_") || it.startsWith("edit_transfer_") }==true
        fun retiredCommand(command:SettlementCommand?)=command is SettlementCommand.RecordTransfer || command is SettlementCommand.ChangeTransfer || command is SettlementCommand.EditTransferAmount
    }
}
