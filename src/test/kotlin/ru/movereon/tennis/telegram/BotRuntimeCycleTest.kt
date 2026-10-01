package ru.movereon.tennis.telegram

import org.junit.jupiter.api.Test
import kotlin.test.*

class BotRuntimeCycleTest {
    @Test fun `incoming updates are handled before a failing maintenance operation`() {
        val handled=mutableListOf<Long>()
        assertFailsWith<TelegramFailure> {
            runBotCycle({listOf(TgUpdate(2),TgUpdate(1))},{handled+=it.id},
                {fail("A nonempty response is not drained")},{throw TelegramFailure(FailureKind.UNCERTAIN)})
        }
        assertEquals(listOf(1L,2L),handled)
    }
    @Test fun `poll drain runs before maintenance can stop another poll`() {
        val order=mutableListOf<String>()
        runBotCycle({order+="receive";emptyList()},{fail("No updates")},
            {order+="finish stopped polls"},{order+="stop pending polls"})
        assertEquals(listOf("receive","finish stopped polls","stop pending polls"),order)
    }
}
