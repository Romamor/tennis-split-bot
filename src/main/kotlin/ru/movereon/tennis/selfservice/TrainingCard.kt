package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

/** One paginated content model for public cards and personal interaction panels. */
internal object TrainingCard {
    data class Row(val user:Long,val name:String,val minutes:Long,val paid:Long,val balance:Long?,val guest:Int=0)
    data class Content(val text:String,val html:String,val page:Int,val pages:Int)
    fun render(t:TrainingRecord,requestedPage:Int,account:(Long)->Account):Content {
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
        val pages=maxOf(1,(rows.size+7)/8);val page=requestedPage.coerceIn(0,pages-1)
        val visible=rows.drop(page*8).take(8)
        val whenText="Когда? ${Screens.date(t.date)} · ${t.startTime}"
        val note=when {
            t.phase==TrainingPhase.REVIEW -> "Пока правки не применены, действует прежний расчёт."
            !known -> "Для расчёта укажи наигранное время."
            else -> "Баланс указан по этой тренировке."
        }
        val text=buildString {
            append(t.title).append('\n').append(whenText).append('\n').append(Screens.phase(t.phase)).append("\n\n")
            if(rows.isEmpty()) append("Пока никто не зарегался")
            else {
                append("Участник | Время | Оплата | Баланс\n")
                visible.forEach { append("${it.name} | ${Screens.hours(it.minutes)} | ${it.paid} ₽ | ${it.balance?.let(Screens::signed) ?: "—"} ₽\n") }
                append("\n$note")
            }
            if(pages>1) append("\nСтраница ${page+1} / $pages")
        }
        val html=buildString {
            append("<h3>${escape(t.title)}</h3><p>${escape(whenText)}<br>${escape(Screens.phase(t.phase))}</p>")
            if(rows.isEmpty()) append("<p>Пока никто не зарегался</p>")
            else {
                append("<table bordered striped compact><tr><th>Участник</th><th>Время</th><th>Оплата</th><th>Баланс</th></tr>")
                visible.forEach {
                    append("<tr><td><a href=\"tg://user?id=${it.user}\">${escape(it.name)}</a></td>")
                    append("<td align=\"right\">${Screens.hours(it.minutes)}</td><td align=\"right\">${it.paid} ₽</td>")
                    append("<td align=\"right\">${it.balance?.let(Screens::signed) ?: "—"} ₽</td></tr>")
                }
                append("</table><p>${escape(note)}</p>")
            }
            if(pages>1) append("<p>Страница ${page+1} / $pages</p>")
        }
        return Content(text,html,page,pages)
    }
    fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
}
