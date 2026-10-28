package ru.movereon.tennis.selfservice

import java.time.Instant
import java.time.ZoneId
import ru.movereon.tennis.application.*
import ru.movereon.tennis.selfservice.FinanceScreens.Companion.signed
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** Display posted changes; both source and amount lead to the existing read-only card. */
internal fun renderBalanceHistory(layout:ScreenLayout,a:Access,history:BalanceHistory,timeZone:String,person:(Long)->String,subject:Long?=null,restricted:Boolean=false):ScreenContent = with(layout) {
    val p=history.page
    val zone=ZoneId.of(timeZone)
    val selected=subject ?: a.userId
    val back=if(subject==null) ScreenAction("finance_balance_history",a.groupId,page=p.index)
        else ScreenAction("finance_member_history",a.groupId,page=p.index,user=selected,back=action.back)
    val parent=if(subject==null) ScreenAction("finance",a.groupId)
        else action.back ?: ScreenAction("finance_balance_members",a.groupId)
    pages(p.index,p.pages,back);row("⬅️ Назад",parent)
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
            val name=if(training) "🏓 ${shortName(requireNotNull(e.trainingTitle))}" else if(restricted)
                (if(e.from==selected) "💸 Перевод отправлен" else "💰 Перевод получен") else
                if(e.from==selected) "💸 → ${shortName(person(requireNotNull(e.to)))}" else "💰 ← ${shortName(person(requireNotNull(e.from)))}"
            val day=date(if(training) requireNotNull(e.trainingDate) else Instant.parse(e.occurredAt).atZone(zone).toLocalDate().toString())
            lines+="$day · $name · ${signed(e.amount)}"
            val link=training || !restricted
            "<tr><td>${if(link) inline(name,target) else TrainingCard.escape(name)}<br>$day</td>"+
                "<td align=\"right\">${if(link) inline(signed(e.amount),target,if(e.amount.signum()>0) "success" else "danger") else signed(e.amount)}</td></tr>"
        }+"</table>"
    val hint="У тренировок — дата занятия, у переводов — последнего изменения. Сумма всех страниц равна текущему балансу. "+
        if(restricted) "Можно открыть только свои тренировки; карточки переводов недоступны." else "Нажми на операцию, чтобы открыть её карточку."
    val heading="История баланса"+(subject?.let { " · ${person(it)}" } ?: "")
    val title="$heading\nСейчас: ${signed(history.balance)}"
    ScreenContent(title+"\n"+if(lines.isEmpty()) "Нет операций, влияющих на баланс." else lines.joinToString("\n")+"\n"+hint,
        "<h3>${TrainingCard.escape(heading)}</h3><p>Сейчас: <b>${signed(history.balance)}</b></p>"+table+if(p.items.isEmpty()) "" else "<p>$hint</p>")
}
