package ru.movereon.tennis.telegram

import org.junit.jupiter.api.Test
import kotlin.test.*

class BotRuntimeCycleTest {
    @Test fun `receipt is durable before processing and background work`() {
        val order=mutableListOf<String>()
        runQueuedBotCycle({ order+="receive";listOf(TgUpdate(1)) },
            { order+="accept";assertEquals(listOf(1L),it.map { u -> u.id }) },
            { order+="process" },{ order+="finish" },{ order+="maintain" })
        assertEquals(listOf("receive","accept","process","finish","maintain"),order)
    }
    @Test fun `failed durable receipt never starts execution or background work`() {
        var executed=false
        assertFailsWith<IllegalStateException> {
            runQueuedBotCycle({ listOf(TgUpdate(1)) },{ error("storage failure") },
                { executed=true },{ executed=true },{ executed=true })
        }
        assertFalse(executed)
    }
    @Test fun `empty Telegram response still processes locally retained events`() {
        val order=mutableListOf<String>()
        runQueuedBotCycle({ emptyList() },{ assertTrue(it.isEmpty());order+="drain" },
            { order+="retry" },{ order+="finish" },{ order+="maintain" })
        assertEquals(listOf("drain","retry","finish","maintain"),order)
    }
}
