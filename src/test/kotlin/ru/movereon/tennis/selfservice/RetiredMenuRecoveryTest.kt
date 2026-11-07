package ru.movereon.tennis.selfservice

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import kotlin.test.*

class RetiredMenuRecoveryTest {
    @TempDir lateinit var dir:Path

    private class MutableClock(var now:Instant=Instant.parse("2026-11-06T10:00:00Z")):Clock() {
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=Clock.fixed(now,zone)
        fun advance(seconds:Long) { now=now.plusSeconds(seconds) }
    }
    private class Fixture(path:Path) {
        val clock=MutableClock()
        val database=Database(path)
        val state=InteractionStore(database,clock)
        val fake=FakeTelegramApi()
        val attempts=mutableListOf<Pair<Long,Long>>()
        val failures=mutableMapOf<Pair<Long,Long>,TelegramFailure>()
        var beforeDelete:(Long,Long)->Unit={ _,_-> }
        val api=object:TelegramApi by fake {
            override fun delete(chatId:Long,messageId:Long) {
                attempts+=chatId to messageId
                beforeDelete(chatId,messageId)
                failures[chatId to messageId]?.let { throw it }
                fake.delete(chatId,messageId)
            }
        }
        val delivery=MessageDelivery(api,state,clock)
        init {
            val service=SettlementService(database)
            for(user in 1L..2L) service.remember(Account(user,"Synthetic $user"))
        }
        fun menu(user:Long=1):Delivery {
            val old=fake.send(user,"Old menu")
            val live=fake.send(user,"Current menu")
            state.sending("personal:$user:$user",0,user,user)
            state.deliveryResult("personal:$user:$user","SENT",live.id)
            state.retirePrivateMenu(user,user,old.id)
            return requireNotNull(state.delivery("retired:$user:${old.id}"))
        }
        fun notice(user:Long=1):Delivery {
            val old=fake.send(user,"Old notice")
            val key="payment-notice:-1:synthetic-${old.id}"
            state.sending(key,0,user,user)
            state.deliveryResult(key,"SENT",old.id)
            state.removePaymentNotice(key)
            return requireNotNull(state.delivery("notice-remove:$user:${old.id}"))
        }
        fun fail(old:Delivery,failure:TelegramFailure) { failures[old.chat to requireNotNull(old.message)]=failure }
    }

    @Test fun `new private reply does not invoke a stopped menu deletion`() {
        val f=Fixture(dir.resolve("foreground.sqlite"))
        val old=f.menu()
        f.beforeDelete={ _,_-> error("Deletion must run only during maintenance") }
        val bot=SelfServiceBot(f.api,f.database,f.fake.bot,f.clock)
        bot.handle(TgUpdate(1,message=TgMessage(100,TgChat(1,"private"),TgUser(1,firstName="Synthetic"),"/start")))
        assertTrue(f.attempts.isEmpty())
        assertTrue(bot.state.completed(1))
        assertTrue(f.fake.messages.containsKey(old.chat to requireNotNull(old.message)))
        val current=assertNotNull(bot.state.delivery("personal:1:1")?.message)
        assertTrue(f.fake.messages.getValue(1L to current).keyboard!!.rows.flatten().any { it.text.contains("Создать тренировку") })
        assertNotNull(bot.state.delivery(old.key))
    }

    @Test fun `one maintenance call processes only a small part of the menu queue`() {
        val f=Fixture(dir.resolve("bounded.sqlite"))
        val queued=List(5) { f.menu() }
        f.delivery.retirePrivateMenus()
        assertEquals(2,f.attempts.size)
        assertEquals(3,queued.count { f.state.delivery(it.key)!=null })
        f.delivery.retirePrivateMenus()
        assertEquals(4,f.attempts.size)
        f.delivery.retirePrivateMenus()
        assertEquals(5,f.attempts.size)
        assertTrue(queued.all { f.state.delivery(it.key)==null })
    }

    @Test fun `uncertain and rate limited menus wait independently while other deletions continue`() {
        val f=Fixture(dir.resolve("menu-retries.sqlite"))
        val uncertain=f.menu()
        val limited=f.menu()
        val other=f.menu(2)
        f.fail(uncertain,TelegramFailure(FailureKind.UNCERTAIN))
        f.fail(limited,TelegramFailure(FailureKind.RETRY_LATER,429,retryAfter=7))
        f.delivery.retirePrivateMenus()
        assertEquals(2,f.attempts.size)
        assertEquals("RETRY",f.state.delivery(uncertain.key)?.status)
        assertEquals("RETRY",f.state.delivery(limited.key)?.status)
        f.failures.clear()
        f.delivery.retirePrivateMenus()
        assertEquals(3,f.attempts.size)
        assertNull(f.state.delivery(other.key))
        repeat(3) { f.delivery.retirePrivateMenus() }
        assertEquals(3,f.attempts.size)
        f.clock.advance(6)
        f.delivery.retirePrivateMenus()
        assertEquals(3,f.attempts.size)
        f.clock.advance(1)
        f.delivery.retirePrivateMenus()
        assertEquals(4,f.attempts.size)
        assertNull(f.state.delivery(limited.key))
        assertNotNull(f.state.delivery(uncertain.key))
        f.clock.advance(53)
        f.delivery.retirePrivateMenus()
        assertEquals(5,f.attempts.size)
        assertNull(f.state.delivery(uncertain.key))
    }

    @Test fun `notice deletion respects long retry after without stopping other messages`() {
        val f=Fixture(dir.resolve("notice-retries.sqlite"))
        val limited=f.notice()
        val other=f.notice(2)
        f.fail(limited,TelegramFailure(FailureKind.RETRY_LATER,429,retryAfter=3600))
        f.delivery.removePaymentNotices()
        assertEquals(2,f.attempts.size)
        assertNotNull(f.state.delivery(limited.key))
        assertNull(f.state.delivery(other.key))
        f.failures.clear()
        f.clock.advance(3599)
        f.delivery.removePaymentNotices()
        assertEquals(2,f.attempts.size)
        f.clock.advance(1)
        f.delivery.removePaymentNotices()
        assertEquals(3,f.attempts.size)
        assertTrue(f.state.pendingNoticeRemovals().isEmpty())
    }

    @Test fun `twenty delayed menu deletions cannot hide the next ready menu`() {
        val f=Fixture(dir.resolve("menu-queue-window.sqlite"))
        val delayed=f.database.withConnectionReuse { List(20) { f.menu() } }
        delayed.forEach { f.fail(it,TelegramFailure(FailureKind.UNCERTAIN)) }
        val ready=f.menu()
        val current=assertNotNull(f.state.delivery("personal:1:1")?.message)
        assertFalse(f.state.retiredPrivateMenus(1).any { it.key==ready.key },"The ready menu starts beyond the bounded SQL page")
        repeat(10) { f.delivery.retirePrivateMenus(1) }
        assertEquals(20,f.attempts.size)
        assertNotNull(f.state.delivery(ready.key))
        f.delivery.retirePrivateMenus(1)
        assertEquals(21,f.attempts.size)
        assertNull(f.state.delivery(ready.key))
        assertTrue(delayed.all { f.state.delivery(it.key)?.status=="RETRY" })
        assertTrue(f.fake.messages.containsKey(1L to current))
        f.delivery.retirePrivateMenus(1)
        assertEquals(21,f.attempts.size,"Delayed failures do not consume another attempt before their deadline")
    }

    @Test fun `one hundred delayed notice deletions cannot hide the next ready notice`() {
        val f=Fixture(dir.resolve("notice-queue-window.sqlite"))
        val delayed=f.database.withConnectionReuse { List(100) { f.notice() } }
        delayed.forEach { f.fail(it,TelegramFailure(FailureKind.UNCERTAIN)) }
        val ready=f.notice(2)
        assertFalse(f.state.pendingNoticeRemovals().any { it.key==ready.key },"The ready notice starts beyond the bounded SQL page")
        repeat(50) { f.delivery.removePaymentNotices() }
        assertEquals(100,f.attempts.size)
        assertNotNull(f.state.delivery(ready.key))
        f.delivery.removePaymentNotices()
        assertEquals(101,f.attempts.size)
        assertNull(f.state.delivery(ready.key))
        assertTrue(delayed.all { f.state.delivery(it.key)?.status=="RETRY" })
        assertTrue(f.state.pendingNoticeRemovals().isEmpty())
        f.delivery.removePaymentNotices()
        assertEquals(101,f.attempts.size,"Delayed failures do not consume another attempt before their deadline")
    }

    @Test fun `failed deletion rows survive a restart for menus and notices`() {
        val path=dir.resolve("restart.sqlite")
        val f=Fixture(path)
        val menu=f.menu()
        val notice=f.notice(2)
        f.fail(menu,TelegramFailure(FailureKind.UNCERTAIN))
        f.fail(notice,TelegramFailure(FailureKind.UNCERTAIN))
        f.delivery.retirePrivateMenus()
        f.delivery.removePaymentNotices()
        assertEquals(2,f.attempts.size)
        f.failures.clear()
        val restoredState=InteractionStore(Database(path),f.clock)
        assertEquals("RETRY",restoredState.delivery(menu.key)?.status)
        assertEquals("RETRY",restoredState.delivery(notice.key)?.status)
        assertTrue(restoredState.retiredPrivateMenus().isEmpty());assertTrue(restoredState.pendingNoticeRemovals().isEmpty())
        val restored=MessageDelivery(f.api,restoredState,f.clock)
        restored.retirePrivateMenus()
        restored.removePaymentNotices()
        assertEquals(2,f.attempts.size)
        f.clock.advance(59);restored.retirePrivateMenus();restored.removePaymentNotices()
        assertEquals(2,f.attempts.size)
        f.clock.advance(1)
        assertEquals(listOf(menu.key),restoredState.retiredPrivateMenus().map { it.key })
        assertEquals(listOf(notice.key),restoredState.pendingNoticeRemovals().map { it.key })
        restored.retirePrivateMenus();restored.removePaymentNotices()
        assertNull(restoredState.delivery(menu.key))
        assertNull(restoredState.delivery(notice.key))
        assertEquals(4,f.attempts.size)
    }

    @Test fun `cleanup propagates fatal authentication and competing poller failures without consuming work`() {
        for(code in listOf(401,409)) for(notice in listOf(false,true)) {
            val f=Fixture(dir.resolve("fatal-$code-$notice.sqlite"))
            val old=if(notice) f.notice() else f.menu()
            val failure=TelegramFailure(FailureKind.REJECTED,code)
            f.fail(old,failure)
            val thrown=assertFailsWith<TelegramFailure> {
                if(notice) f.delivery.removePaymentNotices() else f.delivery.retirePrivateMenus()
            }
            assertSame(failure,thrown)
            assertEquals("RETRY",f.state.delivery(old.key)?.status)
            assertEquals(1,f.attempts.size)
        }
    }

    @Test fun `uncertain first send still propagates a fatal code`() {
        for(code in listOf(401,409)) {
            val f=Fixture(dir.resolve("fatal-send-$code.sqlite"))
            val failure=TelegramFailure(FailureKind.UNCERTAIN,code)
            val api=object:TelegramApi by f.api {
                override fun sendRich(chatId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?):TgMessage=throw failure
            }
            val delivery=MessageDelivery(api,f.state,f.clock)
            val out=Screens.Output("New menu",TgKeyboard(emptyList()),emptySet(),richHtml="<p>New menu</p>")
            assertSame(failure,assertFailsWith<TelegramFailure> {
                delivery.send("personal:1:1",0,1,1,out,"personal:1:1")
            })
        }
    }

    @Test fun `exhausted background time yields cleanup after the new training card is delivered`() {
        val f=Fixture(dir.resolve("background.sqlite"))
        val old=f.menu()
        val bot=SelfServiceBot(f.api,f.database,f.fake.bot,f.clock)
        bot.service.register(SettlementGroup(-1,"Synthetic group","Europe/Moscow"))
        bot.service.rememberMembership(-1,1,true)
        bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("new","Training","2026-11-06","18:30"))
        val deferred=TelegramFailure(FailureKind.RETRY_LATER,retryAfter=1,backgroundDeferred=true)
        f.fail(old,deferred)
        assertSame(deferred,assertFailsWith<TelegramFailure> { bot.maintain() })
        assertEquals("SENT",bot.state.delivery("training:-1:new")?.status)
        assertEquals("RETRY",bot.state.delivery(old.key)?.status)
        f.failures.clear()
        bot.maintain()
        assertNull(bot.state.delivery(old.key))
    }
}
