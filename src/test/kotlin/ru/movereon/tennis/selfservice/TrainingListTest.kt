package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import ru.movereon.tennis.application.*
import ru.movereon.tennis.telegram.TgButton
import kotlin.test.*

class TrainingListTest {
    private fun training(phase:TrainingPhase,title:String="Теннис")=TrainingRecord(-1,phase.name,title,
        "2026-10-06","18:30",phase,1,0,1,emptyList())

    @Test fun `every training status has an ordinary link and cancelled rows stay struck through`() {
        val result=TrainingList.render("Тренировки",TrainingPhase.entries.map(::training)) {
            TgButton("Открыть",url="https://t.me/test_bot?start=l_${it.id}")
        }
        val html=requireNotNull(result.html)
        assertTrue(html.contains(RichTable.OPEN))
        assertEquals(3,Regex("<a href=").findAll(html).count())
        for(phase in TrainingPhase.entries)
            assertTrue(html.contains("<a href=\"https://t.me/test_bot?start=l_${phase.name}\">Теннис</a>"))
        assertTrue(html.contains("<s>06.10.2026</s>"))
        assertTrue(html.contains("<s><a href=\"https://t.me/test_bot?start=l_CANCELLED\">Теннис</a></s>"))
        assertFalse(html.contains("tg-button"))
        assertFalse(html.contains("Открыть"))
        assertEquals(6,Regex("<td align=\"left\">").findAll(html).count())
    }

    @Test fun `labels are escaped and empty lists do not create buttons`() {
        val result=TrainingList.render("<Заголовок>",listOf(training(TrainingPhase.CANCELLED,"<b>Теннис & друзья</b>"))) {
            TgButton("Открыть",url="https://t.me/test_bot?start=l_test")
        }
        assertTrue(result.html!!.contains("&lt;b&gt;Теннис &amp; друзья&lt;/b&gt;"))
        assertFalse(result.html!!.contains("<b>"))
        val empty=TrainingList.render("Записей пока нет.",emptyList()) { error("No link without a training") }
        assertEquals("Записей пока нет.",empty.text)
        assertEquals("<p>Записей пока нет.</p>",empty.html)
    }
}
