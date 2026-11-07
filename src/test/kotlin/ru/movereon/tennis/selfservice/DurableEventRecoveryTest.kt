package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.sql.SQLException
import java.time.*
import kotlin.test.*

/** Real handlers and SQLite, with failures at the network and transaction boundaries. */
class DurableEventRecoveryTest {
    @TempDir lateinit var directory:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-11-06T12:00:00Z")
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=fixed(now,zone)
        override fun instant()=now
    }
    private val clock=TestClock()
    private val fake=FakeTelegramApi()
    private val panels=mutableMapOf<Pair<Long,Long>,TgMessage>()
    private var nextPanel=100L
    private var callbackPanels=0
    private var administratorPanels=0
    private var unavailableMember:Long?=null
    private val api=object:TelegramApi by fake {
        override fun member(chatId:Long,userId:Long):TgMember {
            if(unavailableMember==userId) throw TelegramFailure(FailureKind.UNCERTAIN)
            return fake.member(chatId,userId)
        }
        private fun panel(chatId:Long,userId:Long,text:String,keyboard:TgKeyboard):TgMessage =
            TgMessage(chat=TgChat(chatId,"supergroup"),from=fake.bot,text=text,keyboard=keyboard,
                receiver=TgUser(userId),ephemeralId=nextPanel++).also { panels[chatId to userId]=it }
        override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard):TgMessage {
            callbackPanels++
            return panel(chatId,userId,text,keyboard)
        }
        override fun ephemeralRichAsAdmin(chatId:Long,userId:Long,text:String,html:String,keyboard:TgKeyboard):TgMessage {
            administratorPanels++
            return panel(chatId,userId,text,keyboard)
        }
        override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard) {
            val old=panels[chatId to userId] ?: throw TelegramFailure(FailureKind.MESSAGE_MISSING,400)
            panels[chatId to userId]=old.copy(text=text,keyboard=keyboard)
        }
        override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) {
            if(panels[chatId to userId]?.ephemeralId==ephemeralId) panels.remove(chatId to userId)
        }
    }
    private lateinit var bot:SelfServiceBot
    private fun setup() {
        bot=SelfServiceBot(api,Database(directory.resolve("bot.sqlite")),fake.bot,clock)
        bot.service.register(SettlementGroup(-1,"Synthetic group","Europe/Moscow"))
        fake.members[-1L to fake.bot.id]=TgMember("administrator",user=fake.bot)
        for(user in 1L..3L) {
            bot.service.remember(Account(user,"Synthetic user $user"))
            bot.service.rememberMembership(-1,user,true)
            fake.members[-1L to user]=TgMember(if(user==1L) "administrator" else "member")
        }
    }
    private fun restart() { bot=SelfServiceBot(api,Database(bot.service.database.path),fake.bot,clock) }
    private fun text(id:Long,user:Long,value:String)=TgUpdate(id,TgMessage(id+1000,TgChat(user,"private"),TgUser(user,firstName="Synthetic user $user"),value))
    private fun callback(id:Long,user:Long,message:TgMessage,label:String):TgUpdate {
        val button=message.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        return TgUpdate(id,callback=TgCallback("callback:$id",TgUser(user,firstName="Synthetic user $user"),message,button.callbackData))
    }
    private fun count(sql:String)=bot.service.database.read { c -> sqlQuery(c,sql) { it.getInt(1) }.single() }
    private fun trigger(sql:String)=bot.service.database.write { c -> c.createStatement().use { it.execute(sql) } }
    private fun assertInputReleased(id:Long) {
        bot.service.database.read { c ->
            sqlQuery(c,"SELECT completed,update_json,plan_json,ordering_key,next_attempt_at FROM bot_events WHERE update_id=?",id) {
                assertTrue(it.getBoolean(1))
                assertNull(it.getString(2))
                assertNull(it.getString(3))
                assertNull(it.getString(4))
                assertEquals(0L,it.getLong(5))
            }.single()
        }
    }
    private fun receiptConfirmation(id:Long=100):TgUpdate {
        bot.service.execute(Access(-1,1,true),"seed:payment",SettlementCommand.SendOtherPayment("transfer",2,125))
        val transfer=bot.service.transfer(Access(-1,2),"transfer")
        val key="personal:2:2"
        val action=ScreenAction("finance_receive_save",-1,transfer.id,version=transfer.version)
        val token=bot.state.button(action,2,key)
        val message=fake.send(2,"Confirm synthetic receipt",TgKeyboard(listOf(listOf(TgButton("Confirm",callbackData="n:$token")))))
        bot.state.session(2,2,-1,null)
        bot.state.sending(key,-1,2,2)
        bot.state.deliveryResult(key,"SENT",message.id)
        bot.state.replace(key,setOf(token))
        return callback(id,2,message,"Confirm")
    }
    private fun publishedPoll():TrainingPoll {
        bot.polls.setEnabled(Access(-1,1,true),true)
        val poll=bot.polls.create(Access(-1,1,true),"synthetic-poll","Synthetic training","2026-11-06","18:30","Decline")
        bot.polls.status(poll,"SENDING")
        val sent=fake.sendPoll(-1,"Synthetic poll",poll.options(),TgKeyboard(emptyList()))
        bot.polls.attach(poll,sent.poll!!.id,sent.id)
        return bot.polls.get(-1,poll.id)
    }

    @Test fun `failure before planning preserves raw update beyond Telegram retention and keeps author order`() {
        setup()
        bot.service.execute(Access(-1,1,true),"seed:training",SettlementCommand.CreateTraining("training","Synthetic training","2026-11-06","18:30"))
        bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:training")!!.message!!)
        val opening=callback(100,1,card,"Открыть")
        val laterSameUser=text(101,1,"/start")
        val otherUser=text(102,3,"/start")
        unavailableMember=1
        bot.acceptUpdates(listOf(opening,laterSameUser,otherUser))
        bot.processPending()
        assertNull(bot.state.plan(opening.id),"Failure happened before an EventPlan was produced")
        assertFalse(bot.state.completed(opening.id))
        assertFalse(bot.state.completed(laterSameUser.id),"Later input of the same author must wait")
        assertTrue(bot.state.completed(otherUser.id),"Other authors must keep progressing")
        assertEquals(103L,bot.inbox.offset())
        clock.now=clock.now.plus(Duration.ofHours(25))
        unavailableMember=null
        restart()
        bot.acceptUpdates(emptyList()) // The server no longer has the original update.
        bot.processPending()
        assertTrue(bot.state.completed(opening.id))
        assertTrue(bot.state.completed(laterSameUser.id))
        assertEquals(1,panels.size)
        assertEquals(0,callbackPanels,"The original callback is expired after 25 hours and must not be sent again")
        assertEquals(1,administratorPanels,"A freshly authorized administrator send must recover the delayed panel")
        assertInputReleased(opening.id)
    }

    @Test fun `financial plan is saved in the existing inbox row before the ledger transaction starts`() {
        setup()
        val confirmation=receiptConfirmation()
        bot.acceptUpdates(listOf(confirmation))
        trigger("""CREATE TRIGGER reject_financial_action BEFORE INSERT ON actions
            WHEN NEW.request_id='telegram:100' BEGIN SELECT RAISE(ABORT,'simulated failure before financial commit'); END""")
        assertFailsWith<SQLException> { bot.processPending() }
        val saved=assertNotNull(bot.state.plan(100))
        assertEquals(SettlementCommand.ReceivePayment("transfer",1),saved.command)
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"transfer").status)
        assertEquals(0,count("SELECT COUNT(*) FROM actions WHERE request_id='telegram:100'"))
        trigger("DROP TRIGGER reject_financial_action")
        restart()
        assertEquals(saved,bot.state.plan(100))
        bot.processPending()
        assertEquals(PaymentStatus.ACTIVE,bot.service.transfer(Access(-1,2),"transfer").status)
        assertEquals(1,count("SELECT COUNT(*) FROM actions WHERE request_id='telegram:100'"))
        assertInputReleased(100)
    }

    @Test fun `restart after financial commit before event completion cannot post the receipt twice`() {
        setup()
        val confirmation=receiptConfirmation()
        bot.acceptUpdates(listOf(confirmation))
        trigger("""CREATE TRIGGER reject_event_completion BEFORE UPDATE OF completed ON bot_events
            WHEN NEW.update_id=100 AND NEW.completed=1 BEGIN SELECT RAISE(ABORT,'simulated crash after financial commit'); END""")
        assertFailsWith<SQLException> { bot.processPending() }
        assertFalse(bot.state.completed(100))
        val plan=assertNotNull(bot.state.plan(100))
        assertNotNull(plan.command)
        val balances=bot.service.balances(Access(-1,2))
        assertEquals(PaymentStatus.ACTIVE,bot.service.transfer(Access(-1,2),"transfer").status)
        assertEquals(1,count("SELECT COUNT(*) FROM actions WHERE request_id='telegram:100'"))
        assertEquals(2,count("SELECT COUNT(*) FROM balance_entries WHERE action_id=(SELECT id FROM actions WHERE request_id='telegram:100')"))
        trigger("DROP TRIGGER reject_event_completion")
        restart()
        bot.processPending()
        bot.acceptUpdates(listOf(confirmation))
        bot.processPending()
        assertEquals(balances,bot.service.balances(Access(-1,2)))
        assertEquals(1,count("SELECT COUNT(*) FROM actions WHERE request_id='telegram:100'"))
        assertEquals(2,count("SELECT COUNT(*) FROM balance_entries WHERE action_id=(SELECT id FROM actions WHERE request_id='telegram:100')"))
        assertInputReleased(100)
    }

    @Test fun `poll finalization waits for a post stop empty response and all durable votes across restart`() {
        setup()
        val poll=publishedPoll()
        val telegramId=requireNotNull(poll.telegramId)
        bot.acceptUpdates(emptyList()) // This response precedes stopPoll and cannot establish a barrier.
        bot.polls.beginClose(Access(-1,1,true),poll.id)
        bot.maintain()
        assertTrue(bot.polls.get(-1,poll.id).stopped)
        bot.finishDrainedPolls()
        assertEquals("CLOSING",bot.polls.get(-1,poll.id).status)
        val vote=TgUpdate(100,pollAnswer=TgPollAnswer(telegramId,TgUser(2,firstName="Synthetic user 2"),listOf(0)))
        unavailableMember=2
        bot.acceptUpdates(listOf(vote))
        bot.processPending()
        bot.acceptUpdates(emptyList())
        bot.finishDrainedPolls()
        assertEquals("CLOSING",bot.polls.get(-1,poll.id).status)
        assertTrue(bot.inbox.hasPendingVotes(telegramId))
        assertEquals(0,count("SELECT COUNT(*) FROM trainings"))
        assertTrue(bot.nextPollTimeout(25)>0,"An observed server drain waiting on a delayed local vote must not busy poll")
        unavailableMember=null
        clock.now=clock.now.plusSeconds(3)
        restart()
        bot.processPending()
        bot.finishDrainedPolls()
        assertEquals("CLOSING",bot.polls.get(-1,poll.id).status,"Restart requires a fresh observed server drain")
        bot.acceptUpdates(emptyList())
        bot.finishDrainedPolls()
        bot.finishDrainedPolls()
        val training=bot.service.training(Access(-1,1,true),poll.id)
        assertEquals(setOf(2L),training.players.map { it.userId }.toSet())
        assertEquals(1,count("SELECT COUNT(*) FROM actions WHERE kind='CreateTrainingFromPoll'"))
        assertInputReleased(vote.id)
    }

    @Test fun `negative and withdrawn durable votes release raw choices and voter identity atomically`() {
        setup()
        val poll=publishedPoll()
        val telegramId=requireNotNull(poll.telegramId)
        val positive=TgUpdate(100,pollAnswer=TgPollAnswer(telegramId,TgUser(2),listOf(0)))
        bot.acceptUpdates(listOf(positive))
        bot.processPending()
        assertEquals(1,bot.polls.count(poll))
        val withdrawn=TgUpdate(101,pollAnswer=TgPollAnswer(telegramId,TgUser(2),emptyList()))
        val negative=TgUpdate(102,pollAnswer=TgPollAnswer(telegramId,TgUser(3),listOf(3)))
        bot.acceptUpdates(listOf(withdrawn,negative))
        bot.processPending()
        assertEquals(0,bot.polls.count(poll))
        for(id in 100L..102L) {
            assertInputReleased(id)
            assertEquals(0,count("SELECT COUNT(*) FROM bot_events WHERE update_id=$id AND (user_id IS NOT NULL OR group_id IS NOT NULL)"))
        }
        bot.acceptUpdates(listOf(withdrawn,negative))
        bot.processPending()
        assertInputReleased(withdrawn.id)
        assertInputReleased(negative.id)
    }
}
