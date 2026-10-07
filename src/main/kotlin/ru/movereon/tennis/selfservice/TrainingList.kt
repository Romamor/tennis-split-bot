package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.TrainingPhase
import ru.movereon.tennis.application.TrainingRecord
import ru.movereon.tennis.telegram.TgButton

/** A compact table whose training titles open the existing card via inline callbacks. */
internal object TrainingList {
    fun render(header:String,trainings:List<TrainingRecord>,personalUser:Long?=null,open:(TrainingRecord)->TgButton):ScreenContent {
        fun played(t:TrainingRecord)=t.players.firstOrNull { it.userId==personalUser && it.playing }?.let { Screens.hours(t.rules.minutes(it)) } ?: "—"
        val text=buildString {
            append(header)
            trainings.forEach { append("\n${Screens.date(it.date)} · ${Screens.clean(it.title,34)} · ${Screens.phase(it.phase)}${if(personalUser!=null) " · Играл: ${played(it)}" else ""}") }
        }
        val html=buildString {
            append("<p>${TrainingCard.escape(header).replace("\n","<br>")}</p>")
            if(trainings.isNotEmpty()) {
                append(RichTable.OPEN)
                append("<tr><th align=\"left\">Дата</th><th align=\"left\">Тренировка</th>"+if(personalUser!=null) "<th>Играл</th></tr>" else "</tr>")
                trainings.forEach { t ->
                    val title=TrainingCard.escape(Screens.clean(t.title,34))
                    fun strike(value:String)=if(t.phase==TrainingPhase.CANCELLED) "<s>$value</s>" else value
                    val style=when(t.phase) {
                        TrainingPhase.OPEN -> "primary"
                        TrainingPhase.CLOSED -> "success"
                        TrainingPhase.CANCELLED -> "danger"
                    }
                    val button=open(t)
                    val action=button.callbackData?.let {
                        "<tg-button type=\"callback_data\" style=\"$style\" data=\"${TrainingCard.escape(it)}\">$title</tg-button>"
                    } ?: "<tg-button type=\"url\" style=\"$style\" url=\"${TrainingCard.escape(requireNotNull(button.url))}\">$title</tg-button>"
                    append("<tr><td align=\"left\">${strike(Screens.date(t.date))}</td><td align=\"left\">${strike(action)}</td>"+(if(personalUser!=null) "<td>${played(t)}</td>" else "")+"</tr>")
                }
                append("</table>")
            }
        }
        return ScreenContent(text,html)
    }
}
