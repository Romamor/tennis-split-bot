package ru.movereon.tennis.selfservice

import kotlinx.serialization.json.*
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** Rendering never posts money. Rich inline callbacks reuse the same owner-scoped button batch. */
internal class FinanceScreens(private val service:SettlementService) {
    private val json=Json { ignoreUnknownKeys=true }
    fun render(layout:ScreenLayout,access:Access?,account:(Long)->Account,personRow:(Long,String,ScreenAction)->Unit):ScreenContent = with(layout) {
        val a=requireNotNull(access)
        fun person(id:Long)=clean(account(id).name,36)
        fun footer() { row("⬅️ Назад",ScreenAction("finance",a.groupId)) }
        fun details(t:MoneyTransfer,balances:Map<Long,Long>?=null):ScreenContent {
            fun party(id:Long)=person(id)+(balances?.let { " (${signed(it[id] ?: 0L)})" } ?: "")
            val text="От кого: ${party(t.from)}\nКому: ${party(t.to)}\n${t.amount} ₽ · ${date(t.date)}"
            val html="<p>От кого: ${TrainingCard.profileLink(t.from,person(t.from),account(t.from).username)}${balances?.let { " (${balanceHtml(it[t.from] ?: 0)})" }.orEmpty()}<br>Кому: ${TrainingCard.profileLink(t.to,person(t.to),account(t.to).username)}${balances?.let { " (${balanceHtml(it[t.to] ?: 0)})" }.orEmpty()}<br><b>${t.amount} ₽</b> · ${date(t.date)}</p>"
            return ScreenContent(text,html)
        }
        when(action.kind) {
            "finance" -> {
                val summary=service.financeSummary(a)
                rows+=listOf(button("📤 Перевести",next("finance_send")),button("📥 Принять перевод(${summary.pendingReceiveCount})",next("finance_receive")))
                row("💰 Баланс группы",next("finance_balances"));row("📜 История переводов",next("finance_history"))
                if(service.isAdmin(a)) row("📝 Записать перевод за участников",next("payment_new",option="admin"))
                row("⬅️ Назад",ScreenAction("menu",0))
                val caption=when { summary.balance>0 -> " — оплачено тобой за других участников группы";summary.balance<0 -> " — оплачено за тебя другими участниками группы";else -> "" }
                val pending=buildString {
                    if(summary.pendingSentCount>0) append("\nОтправлено, ждёт подтверждения: ${summary.pendingSentCount} · ${summary.pendingSentAmount} ₽")
                    if(summary.pendingReceiveCount>0) append("\nТебе подтвердить получение: ${summary.pendingReceiveCount} · ${summary.pendingReceiveAmount} ₽")
                    if(summary.pendingSentCount>0 || summary.pendingReceiveCount>0) append("\nОжидающие переводы пока не меняют баланс.")
                }
                ScreenContent("Мои финансы:\n${signed(summary.balance)}$caption$pending","<p>Мои финансы:<br>${balanceHtml(summary.balance)}${TrainingCard.escape(caption+pending).replace("\n","<br>")}</p>")
            }
            "finance_send" -> {
                val p=service.paymentSuggestions(a,page=action.page)
                p.items.forEach { personRow(it.to.value.toLong(),"${person(it.to.value.toLong())} · ${it.amount} ₽",next("finance_send_confirm",target=it.to.value.toLong(),value=it.amount).copy(back=action.copy(page=p.index))) }
                pages(p.index,p.pages)
                row("✍️ Записать свой перевод",next("payment_new",option="").copy(back=action.copy(page=p.index)))
                footer()
                ScreenContent("Перевести\n"+(if(p.total==0) "Сейчас готовых переводов нет." else "Бот подобрал эти переводы для всей группы, стараясь уменьшить их количество.")+"\nМожно выбрать готовый вариант или записать свой перевод — кому хочешь или с кем договорился.")
            }
            "finance_receive" -> {
                val p=service.financePayments(a,action.page,incomingOnly=true)
                p.items.forEach { personRow(it.from,"${person(it.from)} · ${it.amount} ₽ · ${date(it.date)}",next("finance_receive_confirm",id=it.id).copy(back=action.copy(page=p.index))) }
                pages(p.index,p.pages);footer()
                ScreenContent(if(p.total==0) "Нет переводов для подтверждения" else "Принять перевод:")
            }
            "finance_receive_confirm" -> {
                val t=service.transfer(a,action.id)
                checkAccounting(t.to==a.userId && t.status==PaymentStatus.REVIEW,ErrorCode.INVALID_STATE,"Этот перевод недоступен для принятия")
                rows+=listOf(control("✅ Да, получил",next("finance_receive_save"),style="primary"))
                row("⬅️ Назад",action.back ?: next("finance_receive"))
                val data=details(t,service.balances(a));ScreenContent("Деньги пришли?\n"+data.text,"<p>Деньги пришли?</p>"+data.html)
            }
            "finance_received" -> { row("💰 К моим финансам",ScreenAction("finance",a.groupId));ScreenContent("Перевод учтён.\nБаланс обновлён.") }
            "finance_balances" -> {
                val p=service.financeBalances(a,action.page)
                pages(p.index,p.pages);footer()
                peopleTable(this,"Баланс группы",p.items,a.userId) { id -> ScreenAction("payment_new",a.groupId,user=id,back=action.copy(page=p.index)) }
            }
            "finance_payment","finance_payment_cancel_confirm" -> {
                val data=service.paymentDetails(a,action.id);val t=data.transfer
                val isAdmin=service.isAdmin(a)
                val canCancel=t.status!=PaymentStatus.CANCELLED && (isAdmin || t.status==PaymentStatus.REVIEW && t.from==a.userId)
                val content=details(t,if(t.status==PaymentStatus.REVIEW) service.balances(a) else null)
                if(action.kind=="finance_payment_cancel_confirm") {
                    checkAccounting(canCancel,ErrorCode.FORBIDDEN,"Отменить чужой или подтверждённый перевод может только администратор группы")
                    rows+=listOf(control("🚫 Да, отменить",next("finance_payment_cancel_save",version=t.version).copy(back=action.back),style="danger"))
                    row("⬅️ Назад",action.back ?: next("finance_payment"))
                    ScreenContent("Отменить перевод?\n"+content.text,"<p>Отменить перевод?</p>"+content.html)
                } else {
                    if(isAdmin) row("✍️ Изменить сумму",next("finance_payment_edit").copy(back=action))
                    if(canCancel) rows+=listOf(control("🚫 Отменить перевод",next("finance_payment_cancel_confirm").copy(back=action),style="danger"))
                    row(if(action.back==null) "💰 К моим финансам" else "⬅️ Назад",action.back ?: ScreenAction("finance",a.groupId))
                    val status=when(t.status) { PaymentStatus.REVIEW->"Ожидает подтверждения";PaymentStatus.ACTIVE->"Перевод учтён";PaymentStatus.CANCELLED->"Перевод отменён" }
                    val notes=buildString {
                        if(data.administrative) append("\nЗаписал администратор: ${person(t.createdBy)}. Подтверждение участников не требуется.")
                        else if(t.status==PaymentStatus.REVIEW) append("\nБаланс изменится после подтверждения получателем.")
                        data.edits.forEach { edit ->
                            val before=json.decodeFromString<MoneyTransfer>(requireNotNull(edit.before))
                            val after=json.decodeFromString<MoneyTransfer>(edit.after)
                            if(edit.kind=="EditPaymentAmount") append("\nАдминистратор: ${person(edit.actorId)}. Изменил сумму: ${before.amount} ₽ → ${after.amount} ₽.")
                            else append("\n${if(edit.kind=="AdminCancelPayment") "Администратор" else "Отменил"}: ${person(edit.actorId)}. Перевод отменён.")
                        }
                    }
                    ScreenContent("$status\n${content.text}$notes","<p>${TrainingCard.escape(status)}</p>${content.html}<p>${TrainingCard.escape(notes).replace("\n","<br>")}</p>")
                }
            }
            "finance_history","finance_group_history" -> {
                val allGroup=action.option=="all" || action.kind=="finance_group_history"
                val p=service.financePayments(a,action.page,allGroup=allGroup)
                val base=ScreenAction("finance_history",a.groupId,option=if(allGroup) "all" else "mine")
                rows+=listOf(control("👤 Мои",base.copy(option="mine"),style=if(!allGroup) "primary" else null),control("👥 Все",base.copy(option="all"),style=if(allGroup) "primary" else null))
                val edited=service.editedPayments(a,p.items.map { it.id })
                p.items.forEach { row("${date(it.date)} · ${person(it.from)} → ${person(it.to)} · ${it.amount} ₽",next("finance_payment",id=it.id).copy(back=base.copy(page=p.index))) }
                pages(p.index,p.pages,base);footer()
                table("История переводов · ${if(allGroup) "Все" else "Мои"}",listOf("От кого","Кому","Сумма","Дата","Статус"),p.items.map {
                    listOf(person(it.from),person(it.to),"${it.amount} ₽",date(it.date),when(it.status) { PaymentStatus.ACTIVE->"Выполнен";PaymentStatus.REVIEW->"В процессе";PaymentStatus.CANCELLED->"Отменён" }+if(it.id in edited) " · Правка администратора" else "")
                })
            }
            else -> error("Unknown finance screen")
        }
    }
    fun renderForm(layout:ScreenLayout,a:Access,f:InputForm,signature:String,account:(Long)->Account,personRow:(Long,String,ScreenAction)->Unit):ScreenContent = with(layout) {
        fun person(id:Long)=clean(account(id).name,36)
        fun act(kind:String)=ScreenAction(kind,a.groupId,option=signature)
        fun nav(){ rows+=listOf(button("⬅️ Назад",act("payment_back")),control("✖️ Отмена",act("payment_cancel"),style="danger")) }
        if(f.adminPayment && !service.isAdmin(a)) { row("💰 К моим финансам",act("payment_cancel"));return ScreenContent("Права администратора изменились. Закрой ввод перевода.") }
        when(f.kind) {
            "payment_from","payment_to" -> {
                val p=service.financeBalances(a,f.page,exclude=if(f.kind=="payment_to") f.paymentFrom else null)
                pages(p.index,p.pages,act("payment_page"));nav()
                peopleTable(this,if(f.kind=="payment_from") "От кого" else "Кому\nОт кого: ${person(f.paymentFrom)}",p.items,if(f.kind=="payment_to") f.paymentFrom else 0) { id -> act("payment_pick").copy(user=id) }
            }
            "payment_edit" -> {
                val data=service.paymentDetails(a,f.transfer);val t=data.transfer
                checkAccounting(t.version==f.version,ErrorCode.STALE_VERSION,"Перевод изменился. Открой его заново")
                nav()
                val balances=if(t.status==PaymentStatus.REVIEW) service.balances(a) else null
                val text="${when(t.status) { PaymentStatus.REVIEW->"Ожидает подтверждения";PaymentStatus.ACTIVE->"Перевод учтён";PaymentStatus.CANCELLED->"Перевод отменён" }}\nОт кого: ${person(t.from)}${balances?.let { " (${signed(it[t.from] ?: 0)})" }.orEmpty()}\nКому: ${person(t.to)}${balances?.let { " (${signed(it[t.to] ?: 0)})" }.orEmpty()}\n${t.amount} ₽ · ${date(t.date)}\nНапиши новую сумму в рублях."
                ScreenContent(text)
            }
            "payment_amount","payment_ready","payment_duplicate" -> {
                val context=service.paymentDraftContext(a,f.paymentFrom,f.user)
                val recommendation=context.recommendation
                val duplicate=f.kind=="payment_duplicate"
                rows+=listOf(control(if(duplicate) "✅ Да, это ещё один" else if(f.adminPayment) "✅ Записать перевод" else "✅ Перевод отправлен",act("payment_save"),f.amount>0,style="primary"))
                rows+=listOf(control("🔢 Округлить",act("payment_round"),!duplicate && f.roundingAvailable && !f.rounded && f.amount>0 && f.amount%100!=0L))
                rows+=listOf(control("➕ 50 ₽",act("payment_adjust").copy(value=50),!duplicate),control("➕ 100 ₽",act("payment_adjust").copy(value=100),!duplicate))
                rows+=listOf(control("➖ 50 ₽",act("payment_adjust").copy(value=-50),!duplicate && f.amount>0),control("➖ 100 ₽",act("payment_adjust").copy(value=-100),!duplicate && f.amount>0))
                if(recommendation!=null) rows+=listOf(control("🧮 Рекомендуется $recommendation ₽",act("payment_recommend"),!duplicate && f.amount!=recommendation))
                rows+=listOf(control("✍️ Ввести сумму сообщением",act("payment_type"),!duplicate))
                if(duplicate && f.similar.isNotEmpty()) row("📜 Посмотреть предыдущий",act("payment_previous").copy(id=f.similar.first()))
                nav()
                fun balance(id:Long):String {
                    val value=context.balances[id] ?: 0
                    return if(value==0L || duplicate) balanceHtml(value) else inline(signed(value),act("payment_balance").copy(user=id),if(value>0) "success" else "danger")
                }
                fun party(id:Long)=person(id)+" (${signed(context.balances[id] ?: 0)})"
                val prompt=if(f.waitingForAmount || f.kind=="payment_amount") "Напиши сумму в рублях." else "\u00a0"
                val head=if(duplicate) "Это ещё один перевод?" else if(f.adminPayment) "Запись администратором" else "Отправка перевода"
                val tail=if(duplicate) "За последние сутки уже есть такой же перевод." else if(f.adminPayment) "Сразу учтём в балансе. Подтверждение участников не требуется." else "После перевода денег нажми «Перевод отправлен». Учтём сумму после подтверждения получателя."
                ScreenContent("$head\nОт кого: ${party(f.paymentFrom)}\nКому: ${party(f.user)}\n${f.amount} ₽\n$tail\n$prompt",
                    "<p>${TrainingCard.escape(head)}<br>От кого: ${TrainingCard.escape(person(f.paymentFrom))} (${balance(f.paymentFrom)})<br>Кому: ${TrainingCard.escape(person(f.user))} (${balance(f.user)})<br><b>${f.amount} ₽</b><br>${TrainingCard.escape(tail)}<br>$prompt</p>")
            }
            else -> error("Unknown payment form")
        }
    }
    private fun peopleTable(layout:ScreenLayout,title:String,items:List<AccountBalance>,sender:Long,select:(Long)->ScreenAction):ScreenContent {
        val text=title+if(items.isEmpty()) "\nСписок пуст." else "\nУчастник | Баланс | Перевод\n"+items.joinToString("\n") { "${clean(it.account.name,36)} | ${signed(it.balance)} | ${if(it.account.id==sender) "—" else "+"}" }
        val html="<p>${TrainingCard.escape(title).replace("\n","<br>")}</p>"+if(items.isEmpty()) "<p>Список пуст.</p>" else RichTable.OPEN+"<tr><th>Участник</th><th>Баланс</th><th>Перевод</th></tr>"+items.joinToString("") {
            val target=select(it.account.id)
            val balance=if(it.balance==0L || it.account.id==sender) balanceHtml(it.balance) else layout.inline(signed(it.balance),target.copy(value=it.balance.toBigInteger().abs().toAmount()),if(it.balance>0) "success" else "danger")
            "<tr><td>${TrainingCard.profileLink(it.account.id,clean(it.account.name,36),it.account.username)}</td><td align=\"right\">$balance</td><td>${if(it.account.id==sender) "—" else layout.inline("＋",target)}</td></tr>"
        }+"</table>"
        return ScreenContent(text,html)
    }
    private fun table(title:String,headers:List<String>,values:List<List<String>>):ScreenContent {
        val plain=title+if(values.isEmpty()) "\nСписок пуст." else "\n"+(listOf(headers)+values).joinToString("\n") { it.joinToString(" | ") }
        val html="<h3>${TrainingCard.escape(title)}</h3>"+if(values.isEmpty()) "<p>Список пуст.</p>" else RichTable.OPEN+"<tr>"+headers.joinToString("") { "<th>${TrainingCard.escape(it)}</th>" }+"</tr>"+values.joinToString("") { row -> "<tr>"+row.joinToString("") { "<td>${TrainingCard.escape(it)}</td>" }+"</tr>" }+"</table>"
        return ScreenContent(plain,html)
    }
    companion object {
        fun signed(amount:Long)="${if(amount>0) "+" else if(amount<0) "−" else ""}${amount.toBigInteger().abs()} ₽"
        // Rich HTML supports semantic button colors, not arbitrary CSS text colors.
        fun balanceHtml(amount:Long)=if(amount==0L) signed(amount) else "<tg-button type=\"disabled\" style=\"${if(amount>0) "success" else "danger"}\">${signed(amount)}</tg-button>"
        val kinds=setOf("finance","finance_send","finance_receive","finance_receive_confirm","finance_received","finance_balances","finance_payment","finance_history","finance_group_history","finance_payment_cancel_confirm")
        val mutationActions=setOf("finance_payment_cancel_save","finance_payment_edit")
        val retiredActions=setOf("finance_debtors","debts","balances","settled","transfers","transfer","transfer_people","transfer_direction","transfer_amount","suggested_transfer","save_transfer","transfer_date","transfer_note","edit_transfer_amount","save_transfer_amount","review_transfer","confirm_transfer","cancel_transfer","transfer_history")
        fun retiredForm(form:InputForm?)=form?.kind?.let { it.startsWith("transfer_") || it.startsWith("edit_transfer_") }==true
        fun retiredCommand(command:SettlementCommand?)=command is SettlementCommand.RecordTransfer || command is SettlementCommand.ChangeTransfer || command is SettlementCommand.EditTransferAmount
    }
}
