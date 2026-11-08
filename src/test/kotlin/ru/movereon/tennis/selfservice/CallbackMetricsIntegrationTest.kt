package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class CallbackMetricsIntegrationTest {
    @TempDir lateinit var directory:Path
    private val clock=object:Clock() {
        var now=Instant.parse("2026-11-08T12:00:00Z")
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=fixed(now,zone)
    }
    private fun advance(ms:Long) { clock.now=clock.now.plusMillis(ms) }
    private val fake=FakeTelegramApi()
    private val api=object:TelegramApi by fake {
        override fun send(chatId:Long,text:String,keyboard:TgKeyboard?,forceReply:Boolean):TgMessage {
            advance(250);return fake.send(chatId,text,keyboard,forceReply)
        }
        override fun sendRich(chatId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?):TgMessage {
            advance(250);return fake.sendRich(chatId,text,html,keyboard,photoId)
        }
        override fun editRich(chatId:Long,messageId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?) {
            advance(250);fake.editRich(chatId,messageId,text,html,keyboard,photoId)
        }
        override fun edit(chatId:Long,messageId:Long,text:String,keyboard:TgKeyboard?) {
            advance(250);fake.edit(chatId,messageId,text,keyboard)
        }
    }
    private val metrics=UiMetrics(true,clock)
    private fun bot():SelfServiceBot {
        val bot=SelfServiceBot(api,Database(directory.resolve("test.sqlite")),fake.bot,clock,metrics=metrics)
        bot.service.remember(Account(1,"Player"))
        bot.service.register(SettlementGroup(-1,"Example","Europe/Moscow"))
        bot.service.rememberMembership(-1,1,true)
        fake.members[-1L to 1L]=TgMember("member")
        fake.members[-1L to fake.bot.id]=TgMember("administrator")
        bot.handle(TgUpdate(1,TgMessage(100,TgChat(1,"private"),TgUser(1),"/start")))
        return bot
    }
    private fun callback(bot:SelfServiceBot,id:Long,kind:String="finance"):TgUpdate {
        val token=bot.state.button(ScreenAction(kind,-1),1,"personal:1:1")
        return TgUpdate(id,callback=TgCallback("example",TgUser(1),fake.sent.last(),"n:$token"))
    }
    @Test fun `queued callback response measures original receipt and duplicate delivery adds no successful sample`() {
        val bot=bot();val update=callback(bot,2)
        bot.acceptUpdates(listOf(update));advance(500);bot.processPending()
        assertContains(metrics.exposition(),"tennis_ui_response_seconds_sum{category=\"finance\"} 0.75")
        bot.handle(update)
        assertContains(metrics.exposition(),"tennis_ui_response_seconds_count{category=\"finance\"} 1")
    }
    @Test fun `retry keeps original receipt while rejected stale button is not a successful panel`() {
        val bot=bot();val update=callback(bot,2)
        fake.memberFailure=true
        bot.acceptUpdates(listOf(update));bot.processPending()
        assertFalse(metrics.exposition().lineSequence().any { it.startsWith("tennis_ui_response_seconds_count") && !it.endsWith(" 0") })
        advance(60_000);fake.memberFailure=false;bot.processPending()
        assertContains(metrics.exposition(),"tennis_ui_response_seconds_sum{category=\"finance\"} 60.25")
        bot.handle(TgUpdate(3,callback=TgCallback("old",TgUser(1),fake.sent.last(),"n:missing")))
        assertContains(metrics.exposition(),"tennis_ui_response_seconds_count{category=\"finance\"} 1")
    }
}
