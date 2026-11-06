package ru.movereon.tennis.selfservice

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import kotlin.test.*

class AccountPickerRecoveryTest {
    @TempDir lateinit var dir:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-11-06T10:00:00Z")
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=Clock.fixed(now,zone)
        fun advance(seconds:Long) { now=now.plusSeconds(seconds) }
    }
    private class Fixture(path:Path) {
        val clock=TestClock()
        val fake=FakeTelegramApi()
        val requests=mutableListOf<Int>()
        var firstFailure:TelegramFailure?=null
        var acceptBeforeFailure=false
        val api=object:TelegramApi by fake {
            override fun requestUsers(chatId:Long,text:String,requestId:Int):TgMessage {
                requests+=requestId
                val failure=firstFailure.also { firstFailure=null }
                if(failure!=null && !acceptBeforeFailure) throw failure
                val message=fake.send(chatId,text,null,false)
                if(failure!=null) throw failure
                return message
            }
        }
        var bot=SelfServiceBot(api,Database(path),fake.bot,clock)
        val update=TgUpdate(1,message=TgMessage(100,TgChat(1,"private"),TgUser(1,firstName="Synthetic participant"),"Saved event"))
        init {
            bot.service.remember(Account(1,"Synthetic participant"))
            bot.service.register(SettlementGroup(-1,"Synthetic group","Europe/Moscow"))
            bot.service.rememberMembership(-1,1,true)
            fake.members[-1L to 1L]=TgMember("administrator")
            bot.service.execute(Access(-1,1,true),"create",
                SettlementCommand.CreateTraining("training","Synthetic training","2026-11-06","18:30"))
        }
        fun enqueue(kind:String,withOtherEvent:Boolean=false) {
            bot.acceptUpdates(if(withOtherEvent) listOf(update,TgUpdate(2)) else listOf(update))
            bot.state.plan(update.id,EventPlan(1,1,ScreenAction("form",-1),
                form=InputForm(kind,-1,"training",request=71,origin=ScreenAction("add_player_list",-1,"training"))))
        }
        fun restart() { bot=SelfServiceBot(api,Database(bot.service.database.path),fake.bot,clock) }
        fun pickerMessages()=fake.sent.filter { it.text=="Выбери аккаунт Telegram для этой тренировки." }
    }

    @ParameterizedTest @ValueSource(strings=["pick_account","pick_players","pick_add_player"])
    fun `rate limited picker resumes from the shared durable queue after restart`(kind:String) {
        val f=Fixture(dir.resolve("rate-limit-$kind.sqlite"))
        f.firstFailure=TelegramFailure(FailureKind.RETRY_LATER,429,retryAfter=7)
        f.enqueue(kind,withOtherEvent=true)
        assertEquals(429,assertFailsWith<TelegramFailure> { f.bot.processPending() }.code)
        assertEquals(listOf(71),f.requests)
        assertEquals("FAILED",f.bot.state.delivery("picker:1")?.status)
        assertFalse(f.bot.state.completed(1))
        assertNotNull(f.bot.state.plan(1))
        assertEquals(3L,f.bot.inbox.offset(),"Receipt is acknowledged while local picker delivery remains unfinished")
        f.bot.processPending()
        assertTrue(f.bot.state.completed(2),"Unrelated work can continue during the picker retry delay")
        assertEquals(listOf(71),f.requests)
        f.restart()
        f.clock.advance(6)
        f.bot.processPending()
        assertEquals(listOf(71),f.requests)
        f.clock.advance(1)
        f.bot.processPending()
        assertEquals(listOf(71,71),f.requests)
        assertEquals(1,f.pickerMessages().size)
        assertEquals("SENT",f.bot.state.delivery("picker:1")?.status)
        assertTrue(f.bot.state.completed(1))
        assertNull(f.bot.state.plan(1))
        assertTrue(f.bot.inbox.pending().isEmpty())
        assertEquals(kind,f.bot.state.form(1,1)?.kind)
        f.bot.acceptUpdates(listOf(f.update))
        f.bot.processPending()
        assertEquals(listOf(71,71),f.requests,"A repeated received event cannot send another picker")
    }

    @ParameterizedTest @ValueSource(strings=["pick_account","pick_players","pick_add_player"])
    fun `uncertain accepted picker is never resent during replay or restart`(kind:String) {
        val f=Fixture(dir.resolve("uncertain-$kind.sqlite"))
        f.firstFailure=TelegramFailure(FailureKind.UNCERTAIN)
        f.acceptBeforeFailure=true
        f.enqueue(kind)
        f.bot.processPending()
        assertEquals(listOf(71),f.requests)
        assertEquals(1,f.pickerMessages().size)
        assertEquals("UNKNOWN",f.bot.state.delivery("picker:1")?.status)
        assertNull(f.bot.state.delivery("picker:1")?.message)
        assertFalse(f.bot.state.completed(1))
        f.restart()
        f.clock.advance(3)
        f.bot.processPending()
        assertEquals(listOf(71),f.requests)
        assertEquals(1,f.pickerMessages().size)
        assertEquals("UNKNOWN",f.bot.state.delivery("picker:1")?.status)
        assertTrue(f.bot.state.completed(1))
        assertTrue(f.bot.inbox.pending().isEmpty())
        assertEquals(kind,f.bot.state.form(1,1)?.kind)
    }
}
