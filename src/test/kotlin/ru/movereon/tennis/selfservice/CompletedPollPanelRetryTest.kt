package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class CompletedPollPanelRetryTest {
    @TempDir lateinit var directory:Path

    @Test fun `completed poll panel deletion keeps a full hour delay across restart while another group progresses`() {
        val clock=object:Clock() {
            var now=Instant.parse("2026-09-14T12:00:00Z")
            override fun instant()=now
            override fun getZone()=ZoneOffset.UTC
            override fun withZone(zone:ZoneId)=fixed(now,zone)
        }
        val path=directory.resolve("completed-panels.sqlite")
        val fake=FakeTelegramApi()
        val attempts=mutableListOf<Pair<Long,Long>>()
        val visible=mutableSetOf(-1L to 501L)
        var limited=true
        val api=object:TelegramApi by fake {
            override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) {
                assertEquals(1L,userId)
                attempts+=chatId to ephemeralId
                if(chatId==-1L && limited) {
                    limited=false
                    throw TelegramFailure(FailureKind.RETRY_LATER,429,retryAfter=3600)
                }
                visible.remove(chatId to ephemeralId)
            }
        }
        var bot=SelfServiceBot(api,Database(path),fake.bot,clock)
        fun completedPoll(group:Long,panel:Long) {
            val access=Access(group,1,true)
            bot.service.register(SettlementGroup(group,"Тестовая группа","Europe/Moscow"))
            bot.service.remember(Account(1,"Участник"))
            bot.service.rememberMembership(group,1,true)
            fake.members[group to 1L]=TgMember("administrator")
            fake.members[group to fake.bot.id]=TgMember("administrator")
            bot.polls.setEnabled(access,true)
            val poll=bot.polls.create(access,"same-id","Тестовый опрос","2026-09-14","18:30","Не приду")
            bot.polls.attach(poll,"telegram-poll:$group",panel+100)
            bot.polls.beginClose(access,poll.id)
            bot.polls.stopped(poll)
            assertTrue(bot.polls.finish(bot.polls.get(group,poll.id)))
            bot.state.session(1,group,group,null)
            bot.state.rememberEphemeral(1,group,panel)
            bot.state.panel(1,group,ScreenAction("poll_detail",group,poll.id))
        }

        completedPoll(-1,501)
        bot.maintain()
        assertEquals(listOf(-1L to 501L),attempts)
        assertEquals(501L,bot.state.currentEphemeral(1,-1))
        assertTrue(bot.state.completedPollPanels().isEmpty(),"The limited panel must remain saved but not ready")

        bot=SelfServiceBot(api,Database(path),fake.bot,clock)
        visible+=-2L to 502L
        completedPoll(-2,502)
        bot.maintain()
        assertNotNull(bot.state.delivery("training:-2:same-id")?.message)
        assertEquals(listOf(-1L to 501L,-2L to 502L),attempts)
        assertEquals(setOf(-1L to 501L),visible)
        assertNull(bot.state.currentEphemeral(1,-2))
        assertEquals(501L,bot.state.currentEphemeral(1,-1))

        clock.now=clock.now.plusMillis(3_599_999)
        bot=SelfServiceBot(api,Database(path),fake.bot,clock)
        bot.maintain()
        assertEquals(2,attempts.size,"A restart one millisecond before the deadline must not repeat deletion")
        clock.now=clock.now.plusMillis(1)
        bot.maintain()
        assertEquals(listOf(-1L to 501L,-2L to 502L,-1L to 501L),attempts)
        assertTrue(visible.isEmpty())
        assertNull(bot.state.currentEphemeral(1,-1))
        assertTrue(bot.state.completedPollPanels().isEmpty())
        assertEquals("CLOSED",bot.polls.get(-1,"same-id").status)
        assertEquals("CLOSED",bot.polls.get(-2,"same-id").status)
        bot.service.database.verify()
    }
}
