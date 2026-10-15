package ru.movereon.tennis.selfservice

import java.time.Instant
import java.time.ZoneId
import ru.movereon.tennis.application.*
import ru.movereon.tennis.selfservice.FinanceScreens.Companion.signed
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** Display posted changes; both source and amount lead to the existing read-only card. */
internal fun renderBalanceHistory(layout:ScreenLayout,a:Access,history:BalanceHistory,timeZone:String,person:(Long)->String):ScreenContent = with(layout) {
    val p=history.page
    val zone=ZoneId.of(timeZone)
    val back=ScreenAction("finance_balance_history",a.groupId,page=p.index)
    pages(p.index,p.pages,back);row("⬅️ Назад",ScreenAction("finance",a.groupId))
    val lines=mutableListOf<String>()
    val table=if(p.items.isEmpty()) "<p>Баланс ещё не менялся.</p>" else RichTable.OPEN+
        "<tr><th>Дата</th><th>Операция</th><th>Изменение</th></tr>"+p.items.joinToString("") { e ->
            val training=e.trainingId!=null
            val target=ScreenAction(if(training) "my_training" else "finance_payment",a.groupId,
                requireNotNull(e.trainingId ?: e.transferId),back=back)
            val name=if(training) "🏓 ${clean(requireNotNull(e.trainingTitle),30)}" else
                if(e.from==a.userId) "💸 Перевод → ${person(requireNotNull(e.to))}" else "💰 Перевод ← ${person(requireNotNull(e.from))}"
            val change=when(e.kind) {
                "FinishTraining" -> "Учтена"
                "ReopenTraining","MigrateTrainingState" -> "Снова открыта · расчёт отменён"
                "CancelTraining" -> "Отменена · расчёт отменён"
                "CancelPayment","AdminCancelPayment" -> "Отменён"
                "EditPaymentAmount","EditTransferAmount" -> "Сумма изменена"
                "ChangeTransfer" -> if(e.amount.signum()==(if(e.from==a.userId) 1 else -1)) "Учтён" else "Учёт отменён"
                else -> if(training) "Расчёт изменён" else "Учтён"
            }
            val day=date(Instant.parse(e.occurredAt).atZone(zone).toLocalDate().toString())
            lines+="$day · $name · $change · ${signed(e.amount)}"
            "<tr><td>$day</td><td>${inline(name,target)}<br>${TrainingCard.escape(change)}</td>"+
                "<td align=\"right\">${inline(signed(e.amount),target,if(e.amount.signum()>0) "success" else "danger")}</td></tr>"
        }+"</table>"
    val hint="Дата — когда изменился баланс. Нажми на операцию, чтобы открыть её карточку."
    val title="История баланса\nСейчас: ${signed(history.balance)}"
    ScreenContent(title+"\n"+if(lines.isEmpty()) "Баланс ещё не менялся." else lines.joinToString("\n")+"\n"+hint,
        "<h3>История баланса</h3><p>Сейчас: <b>${signed(history.balance)}</b></p>"+table+if(p.items.isEmpty()) "" else "<p>$hint</p>")
}
