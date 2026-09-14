package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

/** One complete content model for public cards and personal interaction panels. */
internal object TrainingCard {
    data class Row(val user:Long,val name:String,val minutes:Long,val paid:Long,val balance:Long?,val guest:Int=0)
    data class Content(val text:String,val html:String)
    fun render(t:TrainingRecord,account:(Long)->Account):Content {
        val slots=t.calculation()
        val allocation=if(slots.players.isNotEmpty() && slots.payments.isNotEmpty()) calculateTraining(slots) else null
        val known=slots.payments.isEmpty() || allocation!=null
        var slotIndex=0
        val rows=t.players.sortedBy { it.ordinal }.filter { it.playing || it.paid>0 }.flatMap { p ->
            val name=Screens.clean(account(p.userId).name,48)
            val share=if(p.playing && p.minutes>0) allocation?.slotShares?.get(slotIndex++ ) ?: 0 else 0
            listOf(Row(p.userId,name,if(p.playing) p.minutes else 0,p.paid,if(known) p.paid-share else null))+
                if(p.playing) (1..p.guestCount).map { number ->
                    val guestShare=if(p.guestMinutes>0) allocation?.slotShares?.get(slotIndex++) ?: 0 else 0
                    Row(p.userId,"$name гость $number",p.guestMinutes,0,if(known) -guestShare else null,number)
                } else emptyList()
        }
        val whenText="${Screens.date(t.date)} ${t.startTime}"
        val note=when {
            t.phase==TrainingPhase.OPEN -> null
            t.phase==TrainingPhase.CANCELLED -> null
            !known -> "Для расчёта укажи наигранное время."
            else -> null
        }
        val text=buildString {
            append(t.title).append('\n').append(whenText).append('\n').append("Статус: ${Screens.phase(t.phase)}").append("\n\n")
            if(rows.isEmpty()) append("Пока никто не зарегался")
            else {
                append(if(t.rules.trackTime) "Участник | Время | Оплата | Баланс\n" else "Участник | Оплата | Баланс\n")
                rows.forEach { append("${it.name} | "+(if(t.rules.trackTime) "${Screens.hours(it.minutes)} | " else "")+"${it.paid} ₽ | ${it.balance?.let(Screens::signed) ?: "—"} ₽\n") }
                if(note!=null) append("\n$note")
            }
        }
        val html=buildString {
            append("<h3>${escape(t.title)}</h3><p>${escape(whenText)}<br>Статус: ${escape(Screens.phase(t.phase))}</p>")
            if(rows.isEmpty()) append("<p>Пока никто не зарегался</p>")
            else {
                append(RichTable.OPEN+"<tr><th>Участник</th>"+(if(t.rules.trackTime) "<th>Время</th>" else "")+"<th>Оплата</th><th>Баланс</th></tr>")
                rows.forEach {
                    append("<tr><td><a href=\"tg://user?id=${it.user}\">${escape(it.name)}</a></td>")
                    if(t.rules.trackTime) append("<td align=\"right\">${Screens.hours(it.minutes)}</td>")
                    append("<td align=\"right\">${it.paid} ₽</td>")
                    append("<td align=\"right\">${it.balance?.let(Screens::signed) ?: "—"} ₽</td></tr>")
                }
                append("</table>")
                if(note!=null) append("<p>${escape(note)}</p>")
            }
        }
        return Content(text,html)
    }
    fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
}
