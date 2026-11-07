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

class PersonalPanelCallbackRecoveryTest {
    @TempDir lateinit var dir:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-09-14T12:00:00Z")
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=fixed(now,zone)
    }
    private inner class Fixture(name:String="panels") {
        val clock=TestClock()
        val fake=FakeTelegramApi()
        val path=dir.resolve("$name.sqlite")
        val calls=mutableListOf<String>()
        val windows=mutableListOf<Long>()
        val panels=mutableMapOf<Long,TgMessage>()
        var callbackFailure:TelegramFailure?=null
        var onCallback:(()->Unit)?=null
        var callbackSends=0
        var administratorSends=0
        var nextPanel=500L
        val api=object:TelegramApi by fake {
            override fun member(chatId:Long,userId:Long):TgMember {
                calls+="member:$userId"
                return fake.member(chatId,userId)
            }
            override fun <T> callbackWindow(remainingMillis:Long,block:()->T):T {
                windows+=remainingMillis
                return block()
            }
            private fun panel(chatId:Long,userId:Long,text:String,keyboard:TgKeyboard)=TgMessage(
                chat=TgChat(chatId,"supergroup"),from=fake.bot,text=text,keyboard=keyboard,
                receiver=TgUser(userId,firstName="Участник $userId"),ephemeralId=nextPanel++).also { panels[userId]=it }
            override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard):TgMessage {
                callbackSends++;calls+="callback"
                onCallback?.invoke()
                callbackFailure?.let { failure ->
                    // A timed-out response can hide a panel that Telegram has already accepted.
                    if(failure.kind==FailureKind.UNCERTAIN) panel(chatId,userId,text,keyboard)
                    throw failure
                }
                return panel(chatId,userId,text,keyboard)
            }
            override fun ephemeralRichAsAdmin(chatId:Long,userId:Long,text:String,html:String,keyboard:TgKeyboard):TgMessage {
                administratorSends++;calls+="administrator"
                return panel(chatId,userId,text,keyboard)
            }
        }
        var bot=SelfServiceBot(api,Database(path),fake.bot,clock)
        val update:TgUpdate
        init {
            bot.service.register(SettlementGroup(-1,"Тестовая группа","Europe/Moscow"))
            fake.members[-1L to fake.bot.id]=TgMember("administrator",user=fake.bot)
            for(user in 1L..2) {
                bot.service.remember(Account(user,"Участник $user"))
                bot.service.rememberMembership(-1,user,true)
                fake.members[-1L to user]=TgMember(if(user==1L) "administrator" else "member",user=TgUser(user))
            }
            bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("training","Тестовая тренировка","2026-09-14","18:30"))
            val out=bot.screens.render(ScreenAction("public",-1,"training"),Access(-1,1,true),"shared-card",null,inGroup=true)
            val card=fake.send(-1,out.text,out.keyboard)
            val button=out.keyboard.rows.flatten().single()
            update=TgUpdate(100,callback=TgCallback("open-panel",TgUser(2,firstName="Участник 2"),card,button.callbackData))
        }
        fun restart() { bot=SelfServiceBot(api,Database(path),fake.bot,clock) }
        fun advance() { clock.now=clock.now.plusSeconds(16) }
        fun assertDelivered() {
            assertTrue(bot.state.completed(update.id))
            assertEquals(panels.getValue(2).ephemeralId,bot.state.currentEphemeral(2,-1))
            assertEquals(1,fake.sent.count { it.chat.id==-1L },"A personal panel must not become another public card")
        }
    }

    @Test fun `a callback processed after sixteen seconds and restart uses administrator delivery`() {
        val f=Fixture()
        f.bot.acceptUpdates(listOf(f.update))
        val accepted=assertNotNull(f.bot.inbox.acceptedAt(f.update.id))
        f.advance();f.restart();f.bot.processPending()
        assertEquals(0,f.callbackSends)
        assertEquals(1,f.administratorSends)
        assertTrue(f.windows.isEmpty())
        assertTrue(accepted<f.clock.millis()-15_000)
        assertEquals(listOf("member:${f.fake.bot.id}","member:2","administrator"),f.calls.takeLast(3))
        f.assertDelivered()
    }

    @Test fun `a persisted plan retains callback age when a retry resumes after restart`() {
        val f=Fixture()
        f.callbackFailure=TelegramFailure(FailureKind.RETRY_LATER,429,retryAfter=1)
        f.bot.acceptUpdates(listOf(f.update));f.bot.processPending()
        val saved=assertNotNull(f.bot.state.plan(f.update.id))
        assertEquals(f.clock.millis(),saved.callbackStartedAt)
        assertFalse(f.bot.state.completed(f.update.id))
        f.advance();f.restart();f.callbackFailure=null;f.bot.processPending()
        assertEquals(1,f.callbackSends)
        assertEquals(1,f.administratorSends)
        assertEquals(listOf(12_000L),f.windows)
        f.assertDelivered()
    }

    @Test fun `a definite callback expiry retries once only after fresh rights and membership checks`() {
        val f=Fixture()
        f.callbackFailure=TelegramFailure(FailureKind.CALLBACK_EXPIRED,400)
        f.bot.acceptUpdates(listOf(f.update));f.bot.processPending()
        assertEquals(1,f.callbackSends)
        assertEquals(1,f.administratorSends)
        assertEquals(listOf("callback","member:${f.fake.bot.id}","member:2","administrator"),f.calls.takeLast(4))
        f.assertDelivered()
        f.restart();f.bot.handle(f.update)
        assertEquals(1,f.callbackSends);assertEquals(1,f.administratorSends)
    }

    @Test fun `an uncertain callback send is never repeated through administrator delivery or replay`() {
        val f=Fixture()
        f.callbackFailure=TelegramFailure(FailureKind.UNCERTAIN)
        f.bot.acceptUpdates(listOf(f.update));f.bot.processPending()
        assertEquals(1,f.callbackSends);assertEquals(0,f.administratorSends)
        assertEquals(1,f.panels.size)
        assertNull(f.bot.state.currentEphemeral(2,-1))
        assertTrue(f.bot.state.completed(f.update.id))
        f.restart();f.bot.processPending();f.bot.handle(f.update)
        assertEquals(1,f.callbackSends);assertEquals(0,f.administratorSends)
        assertEquals(501L,f.nextPanel)
    }

    @Test fun `administrator recovery rejects revoked rights and a departed receiver using fresh checks`() {
        for(revoked in listOf("bot","receiver")) {
            val f=Fixture(revoked)
            f.callbackFailure=TelegramFailure(FailureKind.CALLBACK_EXPIRED,400)
            f.onCallback={
                if(revoked=="bot") f.fake.members[-1L to f.fake.bot.id]=TgMember("member",user=f.fake.bot)
                else f.fake.members[-1L to 2L]=TgMember("left",user=TgUser(2))
            }
            f.bot.acceptUpdates(listOf(f.update));f.bot.processPending()
            assertEquals(1,f.callbackSends);assertEquals(0,f.administratorSends)
            assertTrue(f.bot.state.completed(f.update.id));assertTrue(f.panels.isEmpty())
            if(revoked=="receiver") assertFalse(f.bot.service.isPresent(Access(-1,2)))
        }
    }

    @Test fun `a fresh callback opens normally without using administrator delivery`() {
        val f=Fixture()
        f.bot.handle(f.update)
        assertEquals(1,f.callbackSends);assertEquals(0,f.administratorSends)
        assertEquals(listOf(12_000L),f.windows)
        f.assertDelivered()
    }
}
