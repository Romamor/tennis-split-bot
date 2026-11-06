package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class InboxFairnessTest {
    @TempDir lateinit var dir:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-11-06T12:00:00Z")
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
    }
    private fun start(id:Long,user:Long)=TgUpdate(id,TgMessage(id,TgChat(user,"private"),TgUser(user,firstName="Игрок $user"),"/start"))
    @Test fun `ready older work precedes a deferred failure and matured retries precede later arrivals`() {
        val clock=TestClock();val inbox=UpdateInbox(Database(dir.resolve("order.sqlite")),clock)
        inbox.accept(listOf(start(1,1),start(2,2)))
        inbox.retry(1,3000)
        clock.now=clock.now.plusSeconds(4)
        inbox.accept(listOf(start(3,3)))
        assertEquals(listOf(2L,1L,3L),inbox.pending().map { it.id })
    }
    @Test fun `slow failures are attempted only once per pass while other users proceed`() {
        val clock=TestClock();val fake=FakeTelegramApi();var attempts=0
        val api=object:TelegramApi by fake {
            override fun member(chatId:Long,userId:Long):TgMember {
                clock.now=clock.now.plusSeconds(4)
                if(userId==1L) { attempts++;throw TelegramFailure(FailureKind.UNCERTAIN) }
                return fake.member(chatId,userId)
            }
        }
        val bot=SelfServiceBot(api,Database(dir.resolve("pass.sqlite")),fake.bot,clock)
        bot.service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        for(user in 1L..3L) {
            bot.service.remember(Account(user,"Игрок $user"));bot.service.rememberMembership(-1,user,true)
            fake.members[-1L to user]=TgMember("member")
        }
        bot.acceptUpdates(listOf(start(1,1),start(2,2),start(3,3)))
        bot.processPending(10)
        assertEquals(1,attempts)
        assertFalse(bot.state.completed(1));assertTrue(bot.state.completed(2));assertTrue(bot.state.completed(3))
    }
}
