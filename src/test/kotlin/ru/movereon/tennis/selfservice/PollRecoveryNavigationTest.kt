package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.storage.sqlQuery
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/** Replay actual recovery buttons during their grace period after opening another input flow. */
class PollRecoveryNavigationTest {
    @TempDir lateinit var directory:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private var stopCalls=0
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
        override fun stopPoll(chatId:Long,messageId:Long) { stopCalls++;fake.stopPoll(chatId,messageId) }
    }
    private val clock=Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC)
    private lateinit var bot:SelfServiceBot
    private var event=1L
    private val creator=Access(-1,1,true)
    private fun setup(kind:String):TrainingPoll {
        bot=SelfServiceBot(api,Database(directory.resolve("poll-recovery-navigation.sqlite")),fake.bot,clock)
        bot.service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        fake.members[-1L to fake.bot.id]=TgMember("administrator")
        for(user in 1L..2L) {
            bot.service.remember(Account(user,"Игрок $user"))
            bot.service.rememberMembership(-1,user,true)
            fake.members[-1L to user]=TgMember(if(user==1L) "administrator" else "member")
        }
        bot.polls.setEnabled(creator,true)
        val workflow=PollWorkflow(bot.polls,bot.state,api)
        workflow.publish(bot.polls.create(creator,"poll","Сбор","2026-11-06","18:30","Не приду"))
        val poll=bot.polls.get(-1,"poll")
        vote(poll,2)
        fake.stopFailure=if(kind=="poll_close_retry") TelegramFailure(FailureKind.REJECTED,403)
            else TelegramFailure(FailureKind.MESSAGE_MISSING,400)
        workflow.execute(EventPlan(1,1,ScreenAction("poll_close",-1,poll.id)),creator) { _,_ -> }
        assertTrue(bot.polls.get(-1,poll.id).awaitingCloseRecovery)
        return poll
    }
    private fun latest()=fake.messages.getValue(1L to requireNotNull(bot.state.delivery("personal:1:1")?.message))
    private fun message(text:String) {
        bot.handle(TgUpdate(event++,TgMessage(1000+event,TgChat(1,"private"),TgUser(1,firstName="Игрок 1"),text)))
    }
    private fun button(source:TgMessage,label:String)=requireNotNull(source.keyboard).rows.flatten()
        .single { it.text==label || it.text.endsWith(" $label") }
    private fun click(label:String,source:TgMessage=latest()):TgUpdate {
        val chosen=button(source,label)
        return TgUpdate(event++,callback=TgCallback("cb$event",TgUser(1,firstName="Игрок 1"),source,chosen.callbackData)).also(bot::handle)
    }
    private fun vote(poll:TrainingPoll,user:Long) {
        bot.handle(TgUpdate(event++,pollAnswer=TgPollAnswer(requireNotNull(poll.telegramId),TgUser(user,firstName="Игрок $user"),listOf(1))))
    }
    private fun trainingCount()=bot.service.database.read { c -> sqlQuery(c,"SELECT COUNT(*) FROM trainings") { it.getInt(1) }.single() }

    @ParameterizedTest @ValueSource(strings=["poll_close_retry","poll_close_recover"])
    fun `old recovery mutation preserves dirty input through warning resume and explicit discard`(kind:String) {
        val poll=setup(kind)
        message("/start");click("Мои опросы");click("06.11.2026 · Сбор")
        val actionLabel=if(kind=="poll_close_retry") "Повторить завершение" else "Создать тренировку"
        if(kind=="poll_close_recover") click(actionLabel) // Keep the final confirmation, not the navigation button.
        val oldMessage=latest()
        val oldToken=requireNotNull(button(oldMessage,actionLabel).callbackData).removePrefix("n:")
        assertEquals(kind,bot.state.button(oldToken)?.action?.kind)

        message("/start");click("Создать тренировку");message("Несохранённая тренировка")
        val original=requireNotNull(bot.state.form(1,1))
        assertEquals("date",original.kind);assertEquals("Несохранённая тренировка",original.title)
        assertTrue(original.hasChanges())
        assertNotEquals(oldMessage.id,latest().id)
        assertNotNull(bot.state.button(oldToken),"The old callback remains valid during the replacement grace period")
        val before=bot.polls.get(-1,poll.id)
        val attempts=stopCalls
        fake.stopFailure=null

        fun assertBlocked() {
            val warnings=alerts.size
            val update=click(actionLabel,oldMessage)
            assertTrue(bot.state.completed(update.id))
            assertEquals(warnings+1,alerts.size)
            assertTrue(alerts.last().contains("Сначала заверши или отмени текущий ввод"))
            assertEquals(original,bot.state.form(1,1))
            assertEquals(before,bot.polls.get(-1,poll.id))
            assertEquals(attempts,stopCalls)
            assertEquals(1,bot.polls.count(poll));assertEquals(0,trainingCount())
        }
        assertBlocked()

        message("/start")
        assertTrue(latest().text!!.contains("несохранённые"))
        click("Продолжить ввод")
        assertEquals(original,bot.state.form(1,1))
        assertFalse(latest().text!!.contains("несохранённые"))
        assertBlocked()

        message("/start")
        assertTrue(latest().text!!.contains("несохранённые"))
        click("Сбросить и выйти")
        assertNull(bot.state.form(1,1));assertEquals("Что хочешь сделать?",latest().text)
        click(actionLabel,oldMessage)
        val closing=bot.polls.get(-1,poll.id)
        assertEquals("CLOSING",closing.status);assertTrue(closing.stopped)
        assertNull(bot.state.form(1,1));assertEquals(0,trainingCount())
        vote(poll,1);bot.finishPollsAfterDrain()
        assertEquals(setOf(1L,2L),bot.service.training(creator,poll.id).players.map { it.userId }.toSet())
        assertEquals(1,trainingCount())
    }
}
