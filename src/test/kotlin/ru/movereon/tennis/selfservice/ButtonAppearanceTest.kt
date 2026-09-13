package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import ru.movereon.tennis.telegram.*
import kotlin.test.*

class ButtonAppearanceTest {
    @Test fun `single buttons get icons and specific actions keep semantic Telegram colors`() {
        val open=TgButton("Открыть",callbackData="n:open")
        val join=TgButton("Присоединиться",callbackData="n:join")
        val leave=TgButton("Не участвую",callbackData="n:leave")
        val name=TgButton("Игрок",callbackData="n:person")
        val back=TgButton("⬅️ Назад",callbackData="n:back")
        val rows=listOf(listOf(open),listOf(join),listOf(leave),listOf(name),listOf(back))
        val decorated=ButtonAppearance.keyboard(rows,mapOf(name to ScreenAction("payment_pick",-1,user=1)))
        assertEquals(listOf("🏓 Открыть","🏓 Присоединиться","🚪 Не участвую","👤 Игрок","⬅️ Назад"),decorated.rows.flatten().map { it.text })
        assertEquals(listOf("primary","primary","danger",null,null),decorated.rows.flatten().map { it.style })
        assertEquals(rows.flatten().map { it.callbackData },decorated.rows.flatten().map { it.callbackData })
        assertEquals(decorated,ButtonAppearance.keyboard(decorated.rows))
        assertEquals(1,decorated.rows.first().size)
    }
    @Test fun `multi button rows keep their labels and URL buttons preserve their destinations`() {
        val row=listOf(TgButton("Назад",callbackData="back"),TgButton("Отмена",callbackData="cancel"))
        val url=TgButton("Открыть меню",url="https://t.me/example_bot?start=example")
        val out=ButtonAppearance.keyboard(listOf(row,listOf(url)))
        assertEquals(row,out.rows.first())
        assertEquals(url.url,out.rows.last().single().url)
        assertEquals("primary",out.rows.last().single().style)
        assertEquals("🔗 Открыть меню",out.rows.last().single().text)
    }
}
