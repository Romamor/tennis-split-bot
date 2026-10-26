package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class PanelVisibilityTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val stored=mutableMapOf<Long,TgMessage>()
    private val visible=mutableSetOf<Long>()
    private var nextPanel=100L
    private var seq=1L
    private var sends=0
    private val placements=mutableListOf<Boolean>()
    private var deleteFailure:TelegramFailure?=null
    private var editFailure:TelegramFailure?=null
    private var failurePanel:Long?=null
    private val api=object:TelegramApi by fake {
        override fun ephemeral(chatId:Long,userId:Long,callbackId:String,text:String,keyboard:TgKeyboard,replaceCallbackMessage:Boolean):TgMessage {
            sends++;placements+=replaceCallbackMessage
            val id=nextPanel++
            return TgMessage(chat=TgChat(chatId,"supergroup"),from=fake.bot,text=text,keyboard=keyboard,receiver=TgUser(userId),ephemeralId=id)
                .also { stored[id]=it;visible.add(id) }
        }
        override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard,replaceCallbackMessage:Boolean)=ephemeral(chatId,userId,callbackId,text,keyboard,replaceCallbackMessage)
        override fun editEphemeral(chatId:Long,userId:Long,ephemeralId:Long,text:String,keyboard:TgKeyboard) {
            editFailure?.takeIf { failurePanel==null || failurePanel==ephemeralId }?.let { throw it }
            val old=stored[ephemeralId] ?: throw TelegramFailure(FailureKind.MESSAGE_MISSING,400)
            stored[ephemeralId]=old.copy(text=text,keyboard=keyboard)
        }
        override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard)=editEphemeral(chatId,userId,ephemeralId,text,keyboard)
        override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) { deleteFailure?.let { throw it };visible.remove(ephemeralId);stored.remove(ephemeralId) }
    }
    private lateinit var bot:SelfServiceBot
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite")),fake.bot)
        bot.service.remember(Account(1,"Игрок"));bot.service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        bot.service.rememberMembership(-1,1,true)
        fake.members[-1L to 1L]=TgMember("administrator");fake.members[-1L to fake.bot.id]=TgMember("administrator")
    }
    private fun press(m:TgMessage,label:String,user:Long=1):TgUpdate {
        val b=m.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        return TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(user,firstName="Игрок"),m,b.callbackData)).also(bot::handle)
    }
    private fun panel()=stored.getValue(visible.single())
    @Test fun `each shared card open replaces the panel and nested buttons keep the new panel`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        val ids=mutableSetOf<Long>()
        repeat(4) {
            press(card,"Открыть")
            assertEquals(1,visible.size)
            ids+=requireNotNull(panel().ephemeralId)
        }
        assertEquals(4,ids.size)
        assertEquals(4,sends)
        assertEquals(listOf(true,true,true,true),placements)
        val id=panel().ephemeralId
        press(panel(),"Присоединиться");assertEquals(id,panel().ephemeralId)
        press(panel(),"Время · 0 ч");assertEquals(id,panel().ephemeralId)
    }
    @Test fun `missing panel on an internal callback is recreated without replacing the callback message`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть");val original=panel()
        stored.clear();visible.clear()
        press(original,"Присоединиться")
        assertEquals(listOf(true,false),placements)
        assertEquals(1,visible.size)
        assertTrue(bot.service.training(Access(-1,1,true),"t").players.single().playing)
    }

    @Test fun `failed retirement never creates a second panel and retry opens a fresh one`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть");val old=panel()
        deleteFailure=TelegramFailure(FailureKind.RETRY_LATER,429,1)
        val button=card.keyboard!!.rows.single().single()
        val update=TgUpdate(seq++,callback=TgCallback("retry",TgUser(1),card,button.callbackData))
        assertFailsWith<TelegramFailure> { bot.handle(update) }
        assertEquals(1,sends);assertEquals(setOf(old.ephemeralId),visible)
        assertFalse(bot.state.completed(update.id));assertEquals(old.ephemeralId,bot.state.currentEphemeral(1,-1))
        deleteFailure=null;bot.handle(update)
        assertTrue(bot.state.completed(update.id));assertEquals(2,sends);assertEquals(1,visible.size)
        assertNotEquals(old.ephemeralId,panel().ephemeralId)
        assertTrue(bot.service.training(Access(-1,1,true),"t").players.isEmpty())
    }

    @Test fun `rejected retirement keeps one panel without posting another`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть");val old=panel()
        deleteFailure=TelegramFailure(FailureKind.REJECTED,400)
        press(card,"Открыть")
        assertEquals(1,sends);assertEquals(setOf(old.ephemeralId),visible)
        assertEquals(old.ephemeralId,bot.state.currentEphemeral(1,-1))
    }

    @Test fun `finish poll replaces a panel only after Telegram reports it missing`() {
        setup();val a=Access(-1,1,true);bot.polls.setEnabled(a,true)
        val p=bot.polls.create(a,"p","Теннис","2026-09-14","18:30","Не приду")
        PollWorkflow(bot.polls,bot.state,api).publish(p)
        val message=fake.messages.getValue(-1L to bot.polls.get(-1,"p").message!!)
        press(message,"Завершить сбор");visible.clear();stored.clear()
        press(message,"Завершить сбор")
        assertEquals(1,visible.size);assertTrue(panel().text!!.contains("перейти к учёту"))
        press(panel(),"Завершить сбор");bot.finishPollsAfterDrain()
        assertEquals(1,bot.service.myTrainings(1).page.total)
    }
    @Test fun `poll flag toggles preserve opening training and finishing an existing poll`() {
        setup();val a=Access(-1,1,true)
        bot.service.execute(a,"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть");assertEquals(1,visible.size)
        bot.polls.setEnabled(a,true)
        val p=bot.polls.create(a,"p","Сбор","2026-09-14","18:30","Не приду")
        PollWorkflow(bot.polls,bot.state,api).publish(p)
        val poll=fake.messages.getValue(-1L to bot.polls.get(-1,"p").message!!)
        for(enabled in listOf(true,false,true)) {
            bot.polls.setEnabled(a,enabled)
            press(card,"Открыть")
            assertEquals(1,visible.size);assertTrue(panel().keyboard!!.rows.flatten().any { it.text.endsWith("Присоединиться") })
            press(poll,"Завершить сбор")
            assertEquals(1,visible.size);assertTrue(panel().text!!.contains("перейти к учёту"))
        }
        assertTrue(bot.service.training(a,"t").players.isEmpty())
        assertEquals("OPEN",bot.polls.get(-1,"p").status)
    }
    @Test fun `rejected or uncertain edit does not create another panel`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть")
        for(kind in listOf(FailureKind.REJECTED,FailureKind.UNCERTAIN)) {
            editFailure=TelegramFailure(kind,400)
            press(panel(),"Присоединиться")
            assertEquals(1,sends);assertEquals(1,visible.size)
        }
    }

    @Test fun `background rejection keeps the panel tracked until a fresh shared card open`() {
        for(kind in listOf(FailureKind.REJECTED,FailureKind.UNCERTAIN)) {
            setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
            val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
            press(card,"Открыть");val old=panel()
            bot.service.execute(Access(-1,1,true),"join",SettlementCommand.ChangeAttendance("t",1,AttendanceChange.JOIN))
            editFailure=TelegramFailure(kind,400);bot.maintain()
            assertEquals(old.ephemeralId,bot.state.currentEphemeral(1,-1))
            assertEquals(old,stored[old.ephemeralId]);assertEquals(1,visible.size)
            editFailure=null;val before=sends;press(card,"Открыть")
            assertEquals(before+1,sends);assertNotEquals(old.ephemeralId,panel().ephemeralId)
            assertTrue(panel().text!!.contains("Игрок"))
            // Separate database for the second failure category.
            dir=Files.createDirectory(dir.resolve("next"));stored.clear();visible.clear();fake.messages.clear()
        }
    }
    @Test fun `panel refreshes are bounded survive restart and converge to the latest version`() {
        setup();val a=Access(-1,1,true)
        for(user in 2L..10L) { bot.service.remember(Account(user,"Player $user"));bot.service.rememberMembership(-1,user,true);fake.members[-1L to user]=TgMember("member") }
        bot.service.execute(a,"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        for(user in 1L..10L) press(card,"Открыть",user)
        bot.service.execute(a,"join",SettlementCommand.ChangeAttendance("t",1,AttendanceChange.JOIN))
        bot.maintain();assertEquals(6,bot.state.pendingPanels(20).size)
        bot=SelfServiceBot(api,bot.service.database,fake.bot)
        assertEquals(6,bot.state.pendingPanels(20).size)
        val last=stored.values.single { it.receiver?.id==10L }
        press(last,"Присоединиться",10)
        assertTrue(bot.service.training(a,"t").players.any { it.userId==10L && it.playing })
        assertTrue(bot.state.pendingPanels(20).none { it.user==10L },"The author's response already has the latest version")
        repeat(3) { bot.maintain() }
        assertTrue(bot.state.pendingPanels(20).isEmpty())
        assertEquals(10,visible.size)
        val latestVersion=bot.service.training(a,"t").version
        bot.service.database.read { c ->
            val versions=ru.movereon.tennis.storage.sqlQuery(c,"SELECT json_extract(panel_json,'$.rendered_version') FROM bot_sessions WHERE chat_id=-1 AND ephemeral_id IS NOT NULL") { it.getLong(1) }
            assertEquals(List(10) { latestVersion },versions)
        }
    }
    @Test fun `a failing panel does not block other recipients and is retained for retry`() {
        setup();val a=Access(-1,1,true)
        for(user in 2L..3L) { bot.service.remember(Account(user,"Player $user"));bot.service.rememberMembership(-1,user,true);fake.members[-1L to user]=TgMember("member") }
        bot.service.execute(a,"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        for(user in 1L..3L) press(card,"Открыть",user)
        val first=stored.values.single { it.receiver?.id==1L };failurePanel=first.ephemeralId
        bot.service.execute(a,"join",SettlementCommand.ChangeAttendance("t",1,AttendanceChange.JOIN))
        editFailure=TelegramFailure(FailureKind.UNCERTAIN);bot.maintain()
        assertEquals(first,stored[first.ephemeralId]);assertEquals(first.ephemeralId,bot.state.currentEphemeral(1,-1))
        assertTrue(stored.values.filter { it.receiver?.id!=1L }.all { it.text!!.contains("Игрок") })
        assertFalse(bot.hasPendingPanelRefreshes(),"The failed recipient has a retry delay, not a busy loop")
        assertTrue(bot.nextPollTimeout(25) in 1..6)
    }

    @Test fun `replay after successful panel delivery creates no second panel`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть")
        val id=seq
        bot.service.database.write { c -> sqlUpdate(c,"CREATE TRIGGER fail_completion BEFORE UPDATE OF completed ON bot_events WHEN NEW.update_id=$id AND NEW.completed=1 BEGIN SELECT RAISE(ABORT,'test'); END") }
        val button=card.keyboard!!.rows.single().single()
        val update=TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1),card,button.callbackData))
        assertFailsWith<java.sql.SQLException> { bot.handle(update) }
        val delivered=sends
        bot.service.database.write { c -> sqlUpdate(c,"DROP TRIGGER fail_completion") }
        bot.handle(update)
        assertEquals(delivered,sends);assertEquals(1,visible.size)
    }
}
