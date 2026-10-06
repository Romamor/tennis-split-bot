package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.TrainingPhase
import ru.movereon.tennis.application.TrainingRecord
import ru.movereon.tennis.telegram.TgButton

/** A compact table whose training titles open the existing card via inline callbacks. */
internal object TrainingList {
    fun render(header:String,trainings:List<TrainingRecord>,open:(TrainingRecord)->TgButton):ScreenContent {
        val text=buildString {
            append(header)
            trainings.forEach { append("\n${Screens.date(it.date)} · ${Screens.clean(it.title,34)} · ${Screens.phase(it.phase)}") }
        }
        val html=buildString {
            append("<p>${TrainingCard.escape(header).replace("\n","<br>")}</p>")
            if(trainings.isNotEmpty()) {
                append(RichTable.OPEN)
                append("<tr><th align=\"left\">Дата</th><th align=\"left\">Тренировка</th><th align=\"left\">Статус</th></tr>")
                trainings.forEach { t ->
                    val title=TrainingCard.escape(Screens.clean(t.title,34))
                    fun strike(value:String)=if(t.phase==TrainingPhase.CANCELLED) "<s>$value</s>" else value
                    val button=open(t)
                    val action=button.callbackData?.let {
                        "<tg-button type=\"callback_data\" style=\"link\" data=\"${TrainingCard.escape(it)}\">$title</tg-button>"
                    } ?: "<a href=\"${TrainingCard.escape(requireNotNull(button.url))}\">$title</a>"
                    append("<tr><td align=\"left\">${strike(Screens.date(t.date))}</td><td align=\"left\">${strike(action)}</td><td align=\"left\">${Screens.phase(t.phase)}</td></tr>")
                }
                append("</table>")
            }
        }
        return ScreenContent(text,html)
    }
}
