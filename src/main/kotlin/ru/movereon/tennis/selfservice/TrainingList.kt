package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.TrainingPhase
import ru.movereon.tennis.application.TrainingRecord
import ru.movereon.tennis.telegram.TgButton

/** Formatted labels live in the message; a separate inline callback opens each record. */
internal object TrainingList {
    fun render(header:String,trainings:List<TrainingRecord>,open:(TrainingRecord)->TgButton):ScreenContent {
        val text=buildString {
            append(header)
            trainings.forEach { append("\n${Screens.date(it.date)} · ${Screens.clean(it.title,60)} · ${Screens.phase(it.phase)}") }
        }
        val html=buildString {
            append("<p>${TrainingCard.escape(header).replace("\n","<br>")}</p>")
            if(trainings.isNotEmpty()) {
                append("<table compact>")
                trainings.forEach { t ->
                    val label=TrainingCard.escape("${Screens.date(t.date)} · ${Screens.clean(t.title,60)}")
                    val caption=if(t.phase==TrainingPhase.CANCELLED) "<s>$label</s>" else label
                    val button=open(t)
                    val action=button.callbackData?.let {
                        "<tg-button type=\"callback_data\" style=\"link\" data=\"${TrainingCard.escape(it)}\">Открыть</tg-button>"
                    } ?: "<a href=\"${TrainingCard.escape(requireNotNull(button.url))}\">Открыть</a>"
                    append("<tr><td align=\"left\">$caption<br>${Screens.phase(t.phase)}</td><td align=\"right\">$action</td></tr>")
                }
                append("</table>")
            }
        }
        return ScreenContent(text,html)
    }
}
