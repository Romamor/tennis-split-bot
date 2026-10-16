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
    val table=if(p.items.isEmpty()) "<p>Нет операций, влияющих на баланс.</p>" else RichTable.OPEN+
        "<tr><th>Операция</th><th align=\"right\">Сумма</th></tr>"+p.items.joinToString("") { e ->
            val training=e.trainingId!=null
            val target=ScreenAction(if(training) "my_training" else "finance_payment",a.groupId,
                requireNotNull(e.trainingId ?: e.transferId),back=back)
            fun shortName(value:String):String {
                val name=clean(value,Int.MAX_VALUE)
                return if(name.codePointCount(0,name.length)<=18) name else name.substring(0,name.offsetByCodePoints(0,17))+"…"
            }
            val name=if(training) "🏓 ${shortName(requireNotNull(e.trainingTitle))}" else
                if(e.from==a.userId) "💸 → ${shortName(person(requireNotNull(e.to)))}" else "💰 ← ${shortName(person(requireNotNull(e.from)))}"
            val day=date(Instant.parse(e.occurredAt).atZone(zone).toLocalDate().toString())
            lines+="$day · $name · ${signed(e.amount)}"
            "<tr><td>${inline(name,target)}<br>$day</td>"+
                "<td align=\"right\">${inline(signed(e.amount),target,if(e.amount.signum()>0) "success" else "danger")}</td></tr>"
        }+"</table>"
    val hint="Дата — последнее изменение операции. Сумма всех страниц равна текущему балансу. Нажми на операцию, чтобы открыть её карточку."
    val title="История баланса\nСейчас: ${signed(history.balance)}"
    ScreenContent(title+"\n"+if(lines.isEmpty()) "Нет операций, влияющих на баланс." else lines.joinToString("\n")+"\n"+hint,
        "<h3>История баланса</h3><p>Сейчас: <b>${signed(history.balance)}</b></p>"+table+if(p.items.isEmpty()) "" else "<p>$hint</p>")
}
