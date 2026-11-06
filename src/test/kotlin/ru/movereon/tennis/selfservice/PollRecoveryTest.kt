package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class PollRecoveryTest {
    @TempDir lateinit var directory:Path
    private val fake=FakeTelegramApi()
    private var stopCalls=0
    private var keyboardFailure:TelegramFailure?=null
    private val api=object:TelegramApi by fake {
        override fun stopPoll(chatId:Long,messageId:Long) { stopCalls++;fake.stopPoll(chatId,messageId) }
        override fun editKeyboard(chatId:Long,messageId:Long,keyboard:TgKeyboard) {
            keyboardFailure?.let { throw it }
            fake.editKeyboard(chatId,messageId,keyboard)
        }
    }
    private val clock=Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC)
    private lateinit var bot:SelfServiceBot
    private var event=1L
    private val creator=Access(-1,2)
    private val admin=Access(-1,1,true)
    private fun setup() {
        bot=SelfServiceBot(api,Database(directory.resolve("poll-recovery.sqlite")),fake.bot,clock)
        for(group in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(group,"Группа ${-group}","Europe/Moscow"))
            fake.members[group to fake.bot.id]=TgMember("administrator")
            for(user in 1L..4L) {
                bot.service.remember(Account(user,"Игрок $user"))
                bot.service.rememberMembership(group,user,true)
                fake.members[group to user]=TgMember(if(user==1L) "administrator" else "member")
            }
        }
        bot.polls.setEnabled(admin,true)
    }
    private fun restart() { bot=SelfServiceBot(api,Database(bot.service.database.path),fake.bot,clock) }
    private fun workflow()=PollWorkflow(bot.polls,bot.state,api)
    private fun publish(id:String="p"):TrainingPoll {
        workflow().publish(bot.polls.create(creator,id,"Теннис","2026-11-06","18:30","Не приду"))
        return bot.polls.get(-1,id)
    }
    private fun execute(kind:String,id:String="p",actor:Access=creator)=workflow().execute(
        EventPlan(actor.userId,actor.userId,ScreenAction(kind,actor.groupId,id)),actor) { _,_ -> }
    private fun vote(p:TrainingPoll,user:Long,option:Int?) {
        bot.handle(TgUpdate(event++,pollAnswer=TgPollAnswer(requireNotNull(p.telegramId),TgUser(user,firstName="Игрок $user"),option?.let(::listOf) ?: emptyList())))
    }
    private fun countTrainings(id:String="p")=bot.service.database.read { c ->
        sqlQuery(c,"SELECT COUNT(*) FROM trainings WHERE group_id=-1 AND id=?",id) { it.getInt(1) }.single()
    }
    private fun render(id:String="p",kind:String="poll_detail")=bot.screens.render(ScreenAction(kind,-1,id),creator,"recovery:$id",2)
    private fun labels(out:Screens.Output)=out.keyboard.rows.flatten().map { it.text }
    private fun latest()=fake.messages.values.last { it.chat.id==2L }
    private fun click(label:String) {
        val message=latest()
        val button=requireNotNull(message.keyboard).rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        bot.handle(TgUpdate(event++,callback=TgCallback("cb$event",TgUser(2,firstName="Игрок 2"),message,button.callbackData)))
    }

    @Test fun `deleted poll preserves queued votes and requires explicit recovery after restart`() {
        setup();val p=publish();vote(p,2,0);vote(p,3,1)
        fake.stopFailure=TelegramFailure(FailureKind.MESSAGE_MISSING,400)
        assertEquals("poll_detail",execute("poll_close").screen.kind)
        val failed=bot.polls.get(-1,p.id)
        assertTrue(failed.awaitingCloseRecovery);assertTrue(failed.stopped)
        assertEquals("FAILED",failed.status);assertEquals(2,bot.polls.count(failed))
        vote(p,2,null);vote(p,4,2)
        bot.finishPollsAfterDrain()
        assertEquals(0,countTrainings());assertEquals(2,bot.polls.count(failed))
        val controls=labels(render())
        assertTrue(controls.any { it.contains("Создать тренировку") })
        assertFalse(controls.any { it.contains("Повторить публикацию") || it.contains("Отменить опрос") })
        assertTrue(render(kind="poll_close_recover_confirm").text.contains("Последние полученные голоса"))

        restart();val attempts=stopCalls
        bot.maintain();assertEquals(attempts,stopCalls)
        assertTrue(bot.polls.get(-1,p.id).awaitingCloseRecovery)
        execute("poll_close_recover",actor=admin)
        assertEquals(0,countTrainings());assertTrue(bot.hasClosingPolls())
        restart();vote(p,3,null);vote(p,2,1)
        bot.finishPollsAfterDrain();bot.finishPollsAfterDrain()
        execute("poll_close_recover",actor=admin) // A repeated confirmation must not create another training.
        val training=bot.service.training(creator,p.id)
        assertEquals(setOf(2L,4L),training.players.map { it.userId }.toSet())
        assertEquals(2L,training.createdBy);assertEquals(1,countTrainings())
        assertEquals(1L,bot.service.database.read { c -> sqlQuery(c,"SELECT actor_id FROM actions WHERE kind='CreateTrainingFromPoll'") { it.getLong(1) }.single() })
    }

    @Test fun `unconfirmed 400 and 403 pause closing without discarding republishing or losing later votes`() {
        setup()
        for(code in listOf(400,403)) {
            val id="p$code";val p=publish(id);vote(p,2,0)
            fake.stopFailure=TelegramFailure(FailureKind.REJECTED,code)
            execute("poll_close",id)
            val failed=bot.polls.get(-1,id)
            assertTrue(failed.awaitingCloseRecovery);assertFalse(failed.stopped)
            vote(p,3,1);assertEquals(2,bot.polls.count(failed))
            val controls=labels(render(id))
            assertTrue(controls.any { it.endsWith("Повторить завершение") })
            assertFalse(controls.any { it.contains("Создать тренировку") || it.contains("Повторить публикацию") || it.contains("Отменить опрос") })
            assertFailsWith<AccountingException> { bot.polls.recoverClose(creator,id) }
            assertFailsWith<AccountingException> { bot.polls.discard(creator,id) }
            assertFailsWith<AccountingException> { execute("poll_discard",id) }
            assertFailsWith<AccountingException> { execute("poll_retry",id) }
            val publications=fake.pollOptions.size;workflow().publish(failed)
            assertEquals(publications,fake.pollOptions.size)
            restart();bot.finishPollsAfterDrain();assertEquals(0,countTrainings(id))
            fake.stopFailure=null
            execute("poll_close_retry",id,admin)
            assertTrue(bot.polls.get(-1,id).stopped);assertEquals(0,countTrainings(id))
            vote(p,2,null);vote(p,4,2)
            bot.finishPollsAfterDrain()
            assertEquals(setOf(3L,4L),bot.service.training(creator,id).players.map { it.userId }.toSet())
            assertEquals(publications,fake.pollOptions.size)
        }
    }

    @Test fun `confirmed stop permits recovery if removing controls is permanently rejected or message disappears`() {
        setup()
        for((index,failure) in listOf(TelegramFailure(FailureKind.REJECTED,403),TelegramFailure(FailureKind.MESSAGE_MISSING,400)).withIndex()) {
            val id="markup$index";val p=publish(id);vote(p,2,0)
            keyboardFailure=failure
            execute("poll_close",id)
            val failed=bot.polls.get(-1,id)
            assertTrue(fake.messages.getValue(-1L to requireNotNull(p.message)).poll!!.isClosed)
            assertTrue(failed.awaitingCloseRecovery);assertTrue(failed.stopped)
            bot.finishPollsAfterDrain();assertEquals(0,countTrainings(id))
            execute("poll_close_recover",id);vote(p,3,1)
            bot.finishPollsAfterDrain()
            assertEquals(setOf(2L,3L),bot.service.training(creator,id).players.map { it.userId }.toSet())
            keyboardFailure=null
        }
    }

    @Test fun `temporary stop failures remain retryable across restart and retain the last votes`() {
        setup()
        for((index,failure) in listOf(TelegramFailure(FailureKind.UNCERTAIN),TelegramFailure(FailureKind.RETRY_LATER,429,7)).withIndex()) {
            val id="temporary$index";val p=publish(id);vote(p,2,0)
            fake.stopFailure=failure
            assertSame(failure,assertFailsWith<TelegramFailure> { execute("poll_close",id) })
            assertEquals("CLOSING",bot.polls.get(-1,id).status)
            assertFalse(bot.polls.get(-1,id).awaitingCloseRecovery)
            restart();fake.stopFailure=null;bot.maintain()
            vote(p,2,null);vote(p,4,1)
            assertEquals(0,countTrainings(id))
            bot.finishPollsAfterDrain()
            assertEquals(setOf(4L),bot.service.training(creator,id).players.map { it.userId }.toSet())
        }
    }

    @Test fun `fatal stop errors escape workflow without becoming recoverable failures`() {
        setup()
        for(code in listOf(401,409)) {
            val id="fatal$code";val p=publish(id);vote(p,2,0)
            fake.stopFailure=TelegramFailure(FailureKind.REJECTED,code)
            assertEquals(code,assertFailsWith<TelegramFailure> { execute("poll_close",id) }.code)
            val current=bot.polls.get(-1,id)
            assertEquals("CLOSING",current.status);assertFalse(current.stopped)
            assertFalse(current.awaitingCloseRecovery);assertEquals(1,bot.polls.count(current))
        }
    }

    @Test fun `only the creator or an administrator of the same group can authorize recovery`() {
        setup();publish();fake.stopFailure=TelegramFailure(FailureKind.MESSAGE_MISSING,400);execute("poll_close")
        assertFailsWith<AccountingException> { execute("poll_close_recover",actor=Access(-1,3)) }
        assertFailsWith<AccountingException> { bot.polls.recoverClose(Access(-2,1,true),"p") }
        bot.service.rememberMembership(-1,2,false)
        assertFailsWith<AccountingException> { execute("poll_close_recover") }
        execute("poll_close_recover",actor=admin)
        assertTrue(bot.hasClosingPolls());assertEquals(0,countTrainings())
    }

    @Test fun `completed negative votes erase the durable payload while waiting for recovery`() {
        setup();val p=publish();vote(p,2,0)
        fake.stopFailure=TelegramFailure(FailureKind.REJECTED,403);execute("poll_close")
        val negative=TgUpdate(10000,pollAnswer=TgPollAnswer(requireNotNull(p.telegramId),TgUser(2,firstName="Synthetic private name"),emptyList()))
        bot.inbox.accept(listOf(negative))
        bot.service.database.write { c ->
            sqlUpdate(c,"UPDATE bot_events SET user_id=2,group_id=-1,plan_json=?,next_attempt_at=123 WHERE update_id=10000","""{"user":2,"group":-1}""")
        }
        bot.polls.vote(10000,p,2,null)
        bot.inbox.accept(listOf(negative)) // Re-delivery must not restore erased personal data.
        assertEquals(0,bot.polls.count(p))
        bot.service.database.read { c ->
            sqlQuery(c,"SELECT completed,user_id,group_id,plan_json,update_json,ordering_key,next_attempt_at,received_order FROM bot_events WHERE update_id=10000") {
                assertTrue(it.getBoolean("completed"))
                for(column in listOf("user_id","group_id","plan_json","update_json","ordering_key"))
                    assertNull(it.getString(column),"Completed votes must erase $column")
                assertEquals(0L,it.getLong("next_attempt_at"))
                assertTrue(it.getLong("received_order")>0,"Anonymous receipt sequence may remain for deduplication")
            }.single()
        }
    }

    @Test fun `private recovery buttons require confirmation and open the resulting training after conversion`() {
        setup();val p=publish();fake.stopFailure=TelegramFailure(FailureKind.MESSAGE_MISSING,400);execute("poll_close")
        val token=bot.state.button(ScreenAction("poll_detail",-1,p.id),null,"recovery-entry",permanent=true)
        bot.handle(TgUpdate(event++,TgMessage(event,TgChat(2,"private"),TgUser(2,firstName="Игрок 2"),"/start n_$token")))
        click("Создать тренировку")
        assertTrue(latest().text!!.contains("Создать тренировку по сохранённым голосам?"))
        assertEquals("FAILED",bot.polls.get(-1,p.id).status)
        click("Создать тренировку")
        assertEquals("CLOSING",bot.polls.get(-1,p.id).status);assertEquals(0,countTrainings())
        vote(p,3,1);bot.finishPollsAfterDrain();bot.maintain()
        assertEquals("CLOSED",bot.polls.get(-1,p.id).status)
        assertTrue(latest().text!!.contains("Тренировка создана"))
        assertTrue(latest().keyboard!!.rows.flatten().any { it.text.contains("Открыть тренировку") })
        assertEquals(setOf(3L),bot.service.training(creator,p.id).players.map { it.userId }.toSet())
    }
}
