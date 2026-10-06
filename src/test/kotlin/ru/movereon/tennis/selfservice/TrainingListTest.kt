package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import ru.movereon.tennis.application.*
import ru.movereon.tennis.telegram.TgButton
import kotlin.test.*

class TrainingListTest {
    private fun training(phase:TrainingPhase,title:String="Теннис")=TrainingRecord(-1,phase.name,title,
        "2026-10-06","18:30",phase,1,0,1,emptyList())

    @Test fun `cancelled label is struck while status and open callback remain readable`() {
        val result=TrainingList.render("Тренировки",TrainingPhase.entries.map(::training)) { TgButton("Открыть","n:${it.id}") }
        val html=requireNotNull(result.html)
        assertEquals(1,Regex("<s>").findAll(html).count())
        assertTrue(html.contains("<s>06.10.2026 · Теннис</s><br>Отменена"))
        assertEquals(3,Regex("<td align=\"left\">").findAll(html).count())
        for(phase in TrainingPhase.entries) assertTrue(html.contains("data=\"n:${phase.name}\">Открыть</tg-button>"))
        assertTrue(result.text.contains("Отменена"))
    }

    @Test fun `labels are escaped and empty lists do not create buttons`() {
        val result=TrainingList.render("<Заголовок>",listOf(training(TrainingPhase.CANCELLED,"<b>Теннис & друзья</b>"))) {
            TgButton("Открыть","n:test")
        }
        assertTrue(result.html!!.contains("&lt;b&gt;Теннис &amp; друзья&lt;/b&gt;"))
        assertFalse(result.html!!.contains("<b>"))
        val empty=TrainingList.render("Записей пока нет.",emptyList()) { error("No link without a training") }
        assertEquals("Записей пока нет.",empty.text)
        assertEquals("<p>Записей пока нет.</p>",empty.html)
    }
}
