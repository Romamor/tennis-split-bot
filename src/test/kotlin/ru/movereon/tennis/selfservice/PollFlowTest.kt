package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class PollFlowTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private val panels=mutableMapOf<Long,TgMessage>()
    private var panelId=10000L
    private var failDeleteFor:Long?=null
    private var privateEdits=0
    private var failPrivateEdit=false
    private val api=object:TelegramApi by fake {
        override fun edit(chatId:Long,messageId:Long,text:String,keyboard:TgKeyboard?) {
            if(chatId>0) {
                privateEdits++
                if(failPrivateEdit) { failPrivateEdit=false;throw TelegramFailure(FailureKind.RETRY_LATER,429) }
            }
            fake.edit(chatId,messageId,text,keyboard)
        }
        override fun ephemeral(chatId:Long,userId:Long,callbackId:String,text:String,keyboard:TgKeyboard):TgMessage =
            TgMessage(chat=TgChat(chatId,"supergroup"),from=fake.bot,text=text,keyboard=keyboard,receiver=TgUser(userId),ephemeralId=panelId++).also { panels[userId]=it }
        override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard)=ephemeral(chatId,userId,callbackId,text,keyboard)
        override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard)=editEphemeral(chatId,userId,ephemeralId,text,keyboard)
        override fun editEphemeral(chatId:Long,userId:Long,ephemeralId:Long,text:String,keyboard:TgKeyboard) { panels[userId]=panels.getValue(userId).copy(text=text,keyboard=keyboard) }
        override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) {
            if(failDeleteFor==userId) { failDeleteFor=null;throw TelegramFailure(FailureKind.RETRY_LATER,429) }
            if(panels[userId]?.ephemeralId==ephemeralId) panels.remove(userId)
        }
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
    }
    private val clock=Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"),ZoneOffset.UTC)
    private lateinit var bot:SelfServiceBot
    private var n=1L
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite")),fake.bot,clock)
        for(u in 1L..4) bot.service.remember(Account(u,"Игрок $u"))
        for(g in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            fake.members[g to fake.bot.id]=TgMember("administrator")
            for(u in 1L..4) {
                bot.service.rememberMembership(g,u,true)
                fake.members[g to u]=TgMember(if(u==1L) "administrator" else "member")
            }
        }
    }
    private fun latest(u:Long=2)=fake.messages.values.last { it.chat.id==u }
    private fun message(text:String,u:Long=2) { bot.handle(TgUpdate(n++,message=TgMessage(n,TgChat(u,"private"),TgUser(u,firstName="Игрок $u"),text))) }
    private fun photo(u:Long=2,vararg sizes:TgPhotoSize) { bot.handle(TgUpdate(n++,message=TgMessage(n,TgChat(u,"private"),TgUser(u,firstName="Игрок $u"),photo=sizes.toList()))) }
    private fun click(text:String,u:Long=2,m:TgMessage=latest(u)):TgUpdate {
        val b=m.keyboard!!.rows.flatten().single { it.text==text || it.text.endsWith(" $text") }
        return TgUpdate(n++,callback=TgCallback("cb$n",TgUser(u,firstName="Игрок $u"),m,b.callbackData)).also(bot::handle)
    }
    private fun publish(u:Long=2):TrainingPoll {
        bot.polls.setEnabled(Access(-1,1,true),true)
        message("/start",u);click("Создать опрос",u);click("Оставить «Теннис»",u)
        click("Продолжить · 14.09.2026",u);click("Продолжить · 18:30",u)
        message("Сегодня я диванный чемпион",u)
        assertEquals(listOf("👥 Группа 1"),latest(u).keyboard!!.rows.dropLast(1).flatten().map { it.text })
        click("Группа 1",u);val update=click("Опубликовать",u)
        bot.handle(update);bot.maintain()
        return bot.polls.active(-1).single()
    }
    private fun vote(p:TrainingPoll,u:Long,option:Int?):TgUpdate = TgUpdate(n++,pollAnswer=TgPollAnswer(p.telegramId!!,TgUser(u,firstName="Игрок $u"),option?.let { listOf(it) } ?: emptyList())).also(bot::handle)
    private fun count(sql:String)=bot.service.database.read { c -> sqlQuery(c,sql) { it.getInt(1) }.single() }

    @Test fun `optional photo returns to publication without adding a required step and survives restart`() {
        setup();bot.polls.setEnabled(Access(-1,1,true),true)
        message("/start");click("Создать опрос");click("Оставить «Теннис»")
        click("Продолжить · 14.09.2026");click("Продолжить · 18:30")
        click("Оставить «Не приду»");click("Группа 1")
        assertTrue(latest().keyboard!!.rows.flatten().any { it.text.endsWith(" Опубликовать") })
        click("Добавить фото");assertTrue(latest().text!!.contains("Пришли одно фото"))
        message("не фотография");assertTrue(latest().text!!.contains("Пришли одно фото"))
        click("Назад");assertTrue(latest().keyboard!!.rows.flatten().any { it.text.endsWith(" Опубликовать") })
        click("Добавить фото")
        photo(2,TgPhotoSize("small",100,100),TgPhotoSize("large",800,600))
        assertTrue(latest().text!!.contains("Фото: добавлено"))
        assertTrue(latest().keyboard!!.rows.flatten().any { it.text.endsWith(" Убрать фото") })
        click("Убрать фото");assertFalse(latest().text!!.contains("Фото: добавлено"))
        click("Добавить фото");photo(2,TgPhotoSize("large",800,600))
        click("Заменить фото");photo(2,TgPhotoSize("new-photo",900,700))
        bot=SelfServiceBot(api,Database(bot.service.database.path),fake.bot,clock)
        click("Опубликовать");bot.maintain()
        val poll=bot.polls.active(-1).single()
        assertEquals("new-photo",poll.photoId)
        assertEquals("new-photo",fake.pollPhotos[poll.telegramId])
    }

    @Test fun `feature is per group default off with admin setting and unchanged direct creation`() {
        setup();message("/start")
        assertFalse(latest().keyboard!!.rows.flatten().any { it.text.contains("Создать опрос") })
        assertFailsWith<IllegalArgumentException> { bot.polls.setEnabled(Access(-1,2),true) }
        message("/start",1);click("Настройки",1);click("Настройки групп",1);click("Группа 1",1)
        click("Сбор через опрос: выключен",1)
        assertTrue(bot.polls.enabled(-1));assertFalse(bot.polls.enabled(-2))
        message("/start");click("Создать тренировку");click("Оставить «Теннис»")
        click("Продолжить · 14.09.2026");click("Продолжить · 18:30")
        assertTrue(latest().keyboard!!.rows.flatten().any { it.text.contains("Группа 2") })
        click("Группа 2");click("Опубликовать")
        assertEquals(1,count("SELECT COUNT(*) FROM trainings"));assertEquals(0,count("SELECT COUNT(*) FROM training_polls"))
    }
    @Test fun `member publishes four options once and only positive votes persist without financial changes`() {
        setup();val p=publish()
        assertEquals(listOf("В 18:00","К началу — в 18:30","В 19:00","Сегодня я диванный чемпион"),fake.pollOptions[p.telegramId])
        assertTrue(fake.pinned.contains(-1L to p.message!!));assertEquals(0,count("SELECT COUNT(*) FROM trainings"))
        val positive=vote(p,2,0);bot.handle(positive);vote(p,2,2)
        assertEquals(1,bot.polls.count(p))
        assertEquals(3,bot.service.database.read { c -> sqlQuery(c,"PRAGMA table_info(poll_signups)") { it.getString("name") }.size })
        vote(p,2,3);assertEquals(0,bot.polls.count(p))
        vote(p,3,1);vote(p,3,null);vote(p,4,3);vote(p,999,3)
        assertEquals(0,count("SELECT COUNT(*) FROM users WHERE id=999"))
        assertEquals(0,count("SELECT COUNT(*) FROM poll_signups"))
        assertEquals(0,count("SELECT COUNT(*) FROM actions"))
        assertEquals(0,count("SELECT COUNT(*) FROM bot_events WHERE plan_json IS NOT NULL"))
        assertEquals(0,count("SELECT COUNT(*) FROM bot_events WHERE update_id=${positive.id} AND user_id IS NOT NULL"))
        assertEquals(1,count("SELECT COUNT(*) FROM training_polls"))
    }
    @Test fun `creator closes after draining last votes transfers only signups with zero time and payment exactly once`() {
        setup();val p=publish();vote(p,2,1);vote(p,3,0);vote(p,4,3)
        click("Завершить сбор");assertTrue(latest().text!!.contains("0 ч"))
        assertEquals("primary",latest().keyboard!!.rows.first().single().style)
        click("Назад");assertTrue(latest().text!!.contains("Сбор открыт"))
        click("Завершить сбор");click("Завершить сбор")
        assertTrue(fake.messages[-1L to p.message!!]!!.poll!!.isClosed)
        assertEquals(0,count("SELECT COUNT(*) FROM trainings"))
        vote(p,3,3) // Answer queued immediately before Telegram stopped the poll.
        vote(p,4,2)
        bot.finishPollsAfterDrain();bot.finishPollsAfterDrain();bot.maintain()
        val t=bot.service.training(Access(-1,2),p.id)
        assertEquals(2,t.createdBy);assertEquals(setOf(2L,4L),t.players.map { it.userId }.toSet())
        assertTrue(t.players.all { it.playing && it.minutes==0L && it.paid==0L && it.guestCount==0 })
        assertEquals(TrainingPhase.OPEN,t.phase);assertEquals(0,count("SELECT COUNT(*) FROM balance_entries"))
        assertEquals(1,count("SELECT COUNT(*) FROM actions"));assertEquals(0,count("SELECT COUNT(*) FROM poll_signups"))
        assertTrue(fake.unpinned.contains(-1L to p.message!!))
        val card=bot.state.delivery("training:-1:${p.id}")!!
        assertTrue(fake.pinned.contains(-1L to card.message!!))
        vote(p,3,1);assertEquals(0,count("SELECT COUNT(*) FROM poll_signups"))
        assertTrue(bot.service.database.verify().contains("Схема 9"))
    }
    @Test fun `different user and cross group admin cannot finish and disabled group still permits existing close`() {
        setup();val p=publish()
        val public=fake.messages[-1L to p.message!!]!!
        click("Завершить сбор",3,public)
        assertTrue(alerts.last().contains("создатель"));assertEquals("OPEN",bot.polls.get(-1,p.id).status)
        assertFailsWith<IllegalArgumentException> { bot.polls.beginClose(Access(-2,1,true),p.id) }
        bot.polls.setEnabled(Access(-1,1,true),false)
        assertFailsWith<IllegalArgumentException> { bot.polls.create(Access(-1,2),"another","Теннис","2026-09-14","18:30","Не приду") }
        bot.polls.beginClose(Access(-1,1,true),p.id);bot.maintain();bot.finishPollsAfterDrain()
        assertEquals("CLOSED",bot.polls.get(-1,p.id).status)
    }
    @Test fun `restart resumes closing without duplicating training and unknown telegram polls are ignored`() {
        setup();val p=publish();vote(p,2,1)
        bot.polls.beginClose(Access(-1,2),p.id)
        bot=SelfServiceBot(api,Database(bot.service.database.path),fake.bot,clock)
        bot.maintain();bot.finishPollsAfterDrain()
        bot=SelfServiceBot(api,Database(bot.service.database.path),fake.bot,clock)
        bot.finishPollsAfterDrain();assertEquals(1,count("SELECT COUNT(*) FROM trainings"))
        bot.handle(TgUpdate(n++,pollAnswer=TgPollAnswer("unrelated",TgUser(999,firstName="Unknown"),listOf(3))))
        assertEquals(0,count("SELECT COUNT(*) FROM users WHERE id=999"))
    }
    @Test fun `near midnight options keep the date unambiguous and no extra reply is added`() {
        setup()
        val p=TrainingPoll(-1,"x","Теннис","2026-09-14","00:00","Не приду",2,null,null,"PENDING",false,null,null)
        assertEquals("В 23:30 (13.09)",p.options().first());assertEquals(4,p.options().size)
        val live=publish();val sent=fake.sent.size;vote(live,2,1);vote(live,2,3)
        assertEquals(sent,fake.sent.size)
    }
    @Test fun `failed publication is not automatically resent or converted from an incomplete roster`() {
        setup();fake.pollFailure=TelegramFailure(FailureKind.UNCERTAIN)
        val p=publish()
        assertEquals("UNKNOWN",p.status)
        repeat(2) { bot.maintain() }
        assertEquals(1,fake.pollOptions.size)
        val original=fake.messages.values.single { it.poll!=null }
        vote(p.copy(telegramId=original.poll!!.id),3,1)
        assertEquals(0,bot.polls.count(p))
        bot.polls.attach(p,original.poll.id,original.id)
        assertEquals("UNKNOWN",bot.polls.get(-1,p.id).status)
        assertFailsWith<IllegalArgumentException> { bot.polls.finish(bot.polls.get(-1,p.id)) }
        message("/start");click("Мои опросы");click("Группа 1");click("14.09.2026 · Теннис")
        click("Отменить опрос");click("Отменить опрос")
        assertEquals(0,count("SELECT COUNT(*) FROM training_polls"))
        assertEquals(0,count("SELECT COUNT(*) FROM trainings"))
        assertTrue(fake.messages.getValue(-1L to original.id).poll!!.isClosed)
    }
    @Test fun `disabled or demoted bot group cannot publish from an already prepared form`() {
        setup();bot.polls.setEnabled(Access(-1,1,true),true)
        message("/start");click("Создать опрос");click("Оставить «Теннис»")
        click("Продолжить · 14.09.2026");click("Продолжить · 18:30");click("Оставить «Не приду»");click("Группа 1")
        bot.polls.setEnabled(Access(-1,1,true),false);click("Опубликовать")
        assertEquals(0,count("SELECT COUNT(*) FROM training_polls"));assertTrue(alerts.last().contains("выключен"))
        bot.polls.setEnabled(Access(-1,1,true),true);fake.members[-1L to fake.bot.id]=TgMember("member")
        click("Опубликовать");assertEquals(0,fake.pollOptions.size)
    }

    @Test fun `public button opens one personal confirmation and atomic conversion survives transaction failure`() {
        setup();val p=publish();vote(p,3,1)
        val public=fake.messages.getValue(-1L to p.message!!)
        click("Завершить сбор",2,public)
        assertEquals(listOf("🏁 Завершить сбор","✖️ Закрыть"),panels.getValue(2).keyboard!!.rows.flatten().map { it.text })
        assertEquals(listOf("primary",null),panels.getValue(2).keyboard!!.rows.flatten().map { it.style })
        click("Закрыть",2,panels.getValue(2))
        assertFalse(panels.containsKey(2));assertEquals("OPEN",bot.polls.get(-1,p.id).status)
        assertEquals(0,count("SELECT COUNT(*) FROM trainings"))
        click("Завершить сбор",2,public);val panel=panels.getValue(2)
        click("Завершить сбор",2,public)
        assertNotEquals(panel.ephemeralId,panels.getValue(2).ephemeralId)
        click("Завершить сбор",3,panel)
        assertTrue(alerts.last().contains("другого участника"))
        click("Завершить сбор",2,panels.getValue(2))
        bot.service.database.write { c -> c.createStatement().use { it.execute("CREATE TRIGGER fail_poll_action BEFORE INSERT ON actions WHEN NEW.kind='CreateTrainingFromPoll' BEGIN SELECT RAISE(ABORT,'test'); END") } }
        assertFailsWith<java.sql.SQLException> { bot.finishPollsAfterDrain() }
        assertEquals(0,count("SELECT COUNT(*) FROM trainings"));assertEquals(1,bot.polls.count(p))
        assertEquals("CLOSING",bot.polls.get(-1,p.id).status)
        bot.service.database.write { c -> sqlUpdate(c,"DROP TRIGGER fail_poll_action") }
        bot.finishPollsAfterDrain();assertEquals(1,count("SELECT COUNT(*) FROM trainings"))
    }

    @Test fun `delivered training removes poll panels after restart and retries failed deletion without closing other groups`() {
        setup();val p=publish();vote(p,2,1)
        val public=fake.messages.getValue(-1L to p.message!!)
        click("Завершить сбор",1,public);click("Завершить сбор",2,public)
        click("Завершить сбор",2,panels.getValue(2))
        assertEquals(listOf("✖️ Закрыть"),panels.getValue(2).keyboard!!.rows.flatten().map { it.text })
        bot.finishPollsAfterDrain()
        assertTrue(panels.containsKey(1));assertTrue(panels.containsKey(2))
        // A panel with the same entity id in another group must survive cleanup.
        bot.service.execute(Access(-2,1,true),"other",SettlementCommand.CreateTraining(p.id,"Другая","2026-09-14","18:30"))
        failDeleteFor=1L
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertNotNull(bot.state.delivery("training:-1:${p.id}")?.message)
        assertTrue(panels.containsKey(1));assertFalse(panels.containsKey(2))
        val other=fake.messages.getValue(-2L to bot.state.delivery("training:-2:${p.id}")!!.message!!)
        click("Открыть",3,other);val untouched=panels.getValue(3)
        bot.maintain()
        assertFalse(panels.containsKey(1));assertNull(bot.state.currentEphemeral(1,-1))
        assertEquals(untouched,panels.getValue(3));assertEquals("CLOSED",bot.polls.get(-1,p.id).status)
    }

    @Test fun `private poll card follows votes retractions and completion without new messages or unchanged edits`() {
        setup();val p=publish();val id=latest().id
        vote(p,3,1);bot.maintain()
        assertEquals(id,latest().id);assertTrue(latest().text!!.contains("Записались: 1"))
        val edits=privateEdits;bot.maintain();assertEquals(edits,privateEdits)
        vote(p,3,2);bot.maintain();assertEquals(edits,privateEdits)
        vote(p,4,0);bot.maintain();assertTrue(latest().text!!.contains("Записались: 2"))
        vote(p,3,null);bot.maintain();assertTrue(latest().text!!.contains("Записались: 1"))
        vote(p,4,3);bot.maintain();assertTrue(latest().text!!.contains("Записались: 0"))
        vote(p,3,1)
        val public=fake.messages.getValue(-1L to p.message!!)
        click("Завершить сбор",1,public);click("Завершить сбор",1,panels.getValue(1))
        bot.finishPollsAfterDrain()
        bot.state.privatePollView(2,2,null) // Existing stale OPEN card from before the update.
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(id,latest().id);assertTrue(latest().text!!.contains("Сбор завершён"))
        val labels=latest().keyboard!!.rows.flatten().map { it.text }
        assertFalse(labels.any { it.contains("Завершить сбор") });assertTrue(labels.any { it.contains("Открыть тренировку") })
        assertFalse(latest().text!!.contains("Записались: 0"))
    }
    @Test fun `private card recovery and transient failures preserve current message but never overwrite other menus`() {
        setup();val p=publish();val id=latest().id
        // Simulate the old release: a delivered card with active buttons but no stored view.
        bot.state.privatePollView(2,2,null);vote(p,3,1)
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        failPrivateEdit=true;assertFailsWith<TelegramFailure> { bot.maintain() }
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(id,latest().id);assertTrue(latest().text!!.contains("Записались: 1"))
        click("Меню");val menu=latest()
        vote(p,4,1);bot.maintain();assertEquals(menu,latest())
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain();assertEquals(menu,latest())
    }
    @Test fun `deleted private poll card is not recreated and revoked group access stops updates`() {
        setup();val p=publish();val id=latest().id
        fake.messages.remove(2L to id);vote(p,3,1);bot.maintain()
        assertFalse(fake.messages.containsKey(2L to id));assertTrue(bot.state.privatePollViews().isEmpty())
        message("/start");click("Мои опросы");click("Группа 1");click("14.09.2026 · Теннис")
        val card=latest();fake.members[-1L to 2L]=TgMember("left")
        vote(p,4,1);bot.maintain();assertEquals(card,latest());assertTrue(bot.state.privatePollViews().isEmpty())
    }

    @Test fun `all group switches round trip during polling keep the same poll and roster`() {
        setup();val p=publish();vote(p,2,1);vote(p,3,2)
        val a=Access(-1,1,true)
        repeat(2) {
            bot.polls.setEnabled(a,false)
            bot.service.setGroupTrainingRule(a,"time",false)
            bot.service.setGroupTrainingRule(a,"guests",false)
            bot.maintain()
            assertEquals(p,bot.polls.get(-1,p.id));assertEquals(2,bot.polls.count(p))
            bot.polls.setEnabled(a,true)
            bot.service.setGroupTrainingRule(a,"time",true)
            bot.service.setGroupTrainingRule(a,"guests",true)
        }
        bot.polls.setEnabled(a,false);bot.service.setGroupTrainingRule(a,"time",false)
        vote(p,3,3);vote(p,4,0)
        bot.polls.beginClose(Access(-1,2),p.id);bot.maintain();bot.finishPollsAfterDrain()
        val training=bot.service.training(Access(-1,2),p.id)
        assertEquals(setOf(2L,4L),training.players.map { it.userId }.toSet())
        assertTrue(training.players.all { it.minutes==60L && it.paid==0L });assertFalse(training.rules.trackTime)
        bot.service.setGroupTrainingRule(a,"time",true)
        assertEquals(training.players,bot.service.training(Access(-1,2),p.id).players)
        assertEquals(1,fake.pollOptions.size)
    }

}
