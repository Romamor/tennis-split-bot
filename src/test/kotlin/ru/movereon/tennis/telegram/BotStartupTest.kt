package ru.movereon.tennis.telegram

import org.junit.jupiter.api.Test
import kotlin.test.*

class BotStartupTest {
    private val fake=FakeTelegramApi()
    @Test fun `transient startup requests retry with the same api until identity is obtained`() {
        var calls=0
        val pauses=mutableListOf<Long>()
        val api=object:TelegramApi by fake {
            override fun me():TgUser {
                calls++
                if(calls==1) throw TelegramFailure(FailureKind.UNCERTAIN)
                if(calls==2) throw TelegramFailure(FailureKind.RETRY_LATER,429,7)
                return fake.bot
            }
        }
        assertEquals(fake.bot,awaitBotIdentity(api,pauses::add))
        assertEquals(3,calls);assertEquals(listOf(3000L,7000L),pauses)
    }
    @Test fun `invalid credentials never enter the startup retry loop`() {
        val api=object:TelegramApi by fake { override fun me():TgUser=throw TelegramFailure(FailureKind.REJECTED,401) }
        val failure=assertFailsWith<TelegramFailure> { awaitBotIdentity(api) { fail("Unexpected retry") } }
        assertEquals(401,failure.code)
    }
    @Test fun `shutdown interrupts startup retries`() {
        val api=object:TelegramApi by fake { override fun me():TgUser=throw TelegramFailure(FailureKind.UNCERTAIN) }
        assertFailsWith<InterruptedException> { awaitBotIdentity(api) { throw InterruptedException() } }
    }
}
