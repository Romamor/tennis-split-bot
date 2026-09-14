package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class TrainingRulesTest {
    @TempDir lateinit var dir:Path
    private val api=FakeTelegramApi()
    private val errors=mutableListOf<String>()
    private val panels=mutableMapOf<Long,TgMessage>()
    private var panelId=9000L
    private val telegram=object:TelegramApi by api {
        override fun ephemeral(chatId:Long,userId:Long,callbackId:String,text:String,keyboard:TgKeyboard):TgMessage =
            TgMessage(chat=TgChat(chatId,"supergroup"),from=api.bot,text=text,keyboard=keyboard,receiver=TgUser(userId),ephemeralId=panelId++).also { panels[userId]=it }
        override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard)=ephemeral(chatId,userId,callbackId,text,keyboard)
        override fun editEphemeral(chatId:Long,userId:Long,ephemeralId:Long,text:String,keyboard:TgKeyboard) { panels[userId]=panels.getValue(userId).copy(text=text,keyboard=keyboard) }
        override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard)=editEphemeral(chatId,userId,ephemeralId,text,keyboard)
        override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) { panels.remove(userId) }
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) errors+=text }
    }
    private lateinit var bot:SelfServiceBot
    private var seq=10L
    private val admin=Access(-1,1,true)
    private val member=Access(-1,2)
    private fun setup() {
        bot=SelfServiceBot(telegram,Database(dir.resolve("rules.sqlite")),api.bot)
        for(u in 1L..3) bot.service.remember(Account(u,"Игрок $u"))
        for(g in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            api.members[g to api.bot.id]=TgMember("administrator")
            for(u in 1L..3) {
                bot.service.rememberMembership(g,u,true)
                api.members[g to u]=TgMember(if(u==1L && g==-1L) "administrator" else "member")
            }
        }
    }
    private fun run(cmd:SettlementCommand,a:Access=admin)=bot.service.execute(a,"test:${seq++}",cmd)
    private fun create(id:String="t",a:Access=admin)=run(SettlementCommand.CreateTraining(id,"Теннис","2026-09-14","18:30"),a)
    private fun change(u:Long,c:AttendanceChange,v:Long=0,id:String="t",a:Access=admin)=run(SettlementCommand.ChangeAttendance(id,u,c,v),a)
    private fun t(id:String="t")=bot.service.training(admin,id)
    private fun render(kind:String="participation",u:Long=2,id:String="t")=bot.screens.render(ScreenAction(kind,-1,id),Access(-1,u,u==1L),"test:$kind:$u",u)
    private fun labels(out:Screens.Output)=out.keyboard.rows.flatten().map { it.text }
    private fun message(text:String,u:Long=1) { bot.handle(TgUpdate(seq++,message=TgMessage(seq,TgChat(u,"private"),TgUser(u,firstName="Игрок $u"),text))) }
    private fun latest(u:Long=1)=api.messages.values.last { it.chat.id==u }
    private fun click(label:String,u:Long=1) {
        clickOn(latest(u),u,label)
    }
    private fun clickOn(m:TgMessage,u:Long,label:String) {
        val b=m.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        bot.handle(TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(u,firstName="Игрок $u"),m,b.callbackData)))
    }
    @Test fun `defaults retain guests and individual time and settings are admin only and group scoped`() {
        setup();assertEquals(TrainingRules(),bot.service.groupTrainingRules(-1))
        message("/start");click("Настройки");click("Настройки групп");click("Группа 1")
        assertTrue(latest().text!!.contains("новым и открытым"))
        click("Гости: разрешены");click("Учёт времени: включён")
        assertEquals(TrainingRules(false,false),bot.service.groupTrainingRules(-1))
        assertEquals(TrainingRules(),bot.service.groupTrainingRules(-2))
        assertFailsWith<AccountingException> { bot.service.setGroupTrainingRule(member,"time",true) }
        assertFailsWith<AccountingException> { bot.service.setGroupTrainingRule(Access(-2,1),"guests",false) }
        create();assertEquals(TrainingRules(false,false),t().rules)
        create("other",Access(-2,2));assertEquals(TrainingRules(),bot.service.training(Access(-2,2),"other").rules)
    }
    @Test fun `hidden time means one hour for joining admin bulk add leaving and joining again`() {
        setup();bot.service.setGroupTrainingRule(admin,"time",false);create()
        change(2,AttendanceChange.JOIN,a=member)
        assertEquals(60,t().players.single().minutes)
        change(2,AttendanceChange.LEAVE,a=member);assertTrue(t().players.isEmpty())
        change(2,AttendanceChange.JOIN,a=member)
        run(SettlementCommand.AddPlayers("t",t().version,listOf(1,3)))
        assertTrue(t().players.all { it.minutes==60L && it.paid==0L })
        val personal=render()
        assertFalse(labels(personal).any { it.contains("Время") || it.contains("ч") && it.contains("0,5") })
        assertFalse(personal.text.contains("1 ч"));assertFalse(personal.text.contains("| Время |"))
        for(kind in listOf("public","my_training","training")) {
            val card=render(kind,1)
            assertFalse(card.richHtml!!.contains("<th>Время</th>"));assertFalse(card.richHtml.contains("1 ч"))
        }
        assertFailsWith<AccountingException> { render("participation_time") }
        assertFailsWith<AccountingException> { change(2,AttendanceChange.ADJUST_MINUTES,30) }
        assertFailsWith<AccountingException> { change(2,AttendanceChange.SET_MINUTES,90,a=member) }
        val row=t().players.single { it.userId==2L }
        assertFailsWith<AccountingException> { run(SettlementCommand.SaveAttendance("t",2,row,row.copy(minutes=90))) }
    }
    @Test fun `guests disabled removes buttons and rejects both modern and old commands and drafts`() {
        setup();bot.service.setGroupTrainingRule(admin,"guests",false);create();change(2,AttendanceChange.JOIN,a=member)
        assertFalse(labels(render()).any { it.contains("гост") })
        assertTrue(render().keyboard.rows.all { it.isNotEmpty() })
        assertTrue(labels(render()).any { it.contains("Время") })
        for(c in listOf(AttendanceChange.GUEST,AttendanceChange.ADJUST_GUESTS,AttendanceChange.SET_GUEST_MINUTES))
            assertFailsWith<AccountingException> { change(2,c,1) }
        val row=t().players.single()
        assertFailsWith<AccountingException> { run(SettlementCommand.SaveAttendance("t",2,row,row.copy(guestCount=1))) }
        assertFailsWith<AccountingException> { bot.service.previewAttendance(row,AttendanceChange.ADJUST_GUESTS,1,t().rules) }
        change(2,AttendanceChange.ADJUST_MINUTES,30);assertEquals(30,t().players.single().minutes)
    }
    @Test fun `equal split includes permitted guests and preserves closed history when defaults change`() {
        setup();bot.service.setGroupTrainingRule(admin,"time",false);create()
        change(2,AttendanceChange.JOIN);change(3,AttendanceChange.JOIN)
        change(2,AttendanceChange.ADJUST_GUESTS,1);change(3,AttendanceChange.SET_PAID,300)
        assertEquals(60,t().players.first { it.userId==2L }.guestMinutes)
        run(SettlementCommand.FinishTraining("t",t().version))
        assertEquals(mapOf(2L to -200L,3L to 200L),bot.service.balances(admin))
        val history=bot.service.history(admin,"t");val before=t()
        bot.service.setGroupTrainingRule(admin,"time",true);bot.service.setGroupTrainingRule(admin,"guests",false)
        assertEquals(before,t());assertEquals(history,bot.service.history(admin,"t"))
        run(SettlementCommand.ReopenTraining("t",t().version));assertTrue(t().rules.trackTime);assertFalse(t().rules.guestsEnabled)
        create("new");assertEquals(TrainingRules(false,true),t("new").rules)
    }
    @Test fun `open training preserves input adopts live rules and poll uses current defaults`() {
        setup();create("old");change(2,AttendanceChange.JOIN,id="old");change(2,AttendanceChange.SET_MINUTES,90,id="old");change(2,AttendanceChange.ADJUST_GUESTS,1,id="old")
        val before=t("old")
        bot.polls.setEnabled(admin,true)
        val poll=bot.polls.create(member,"poll","Теннис","2026-09-14","18:30","Не приду")
        bot.polls.attach(poll,"telegram-poll",100)
        bot.polls.vote(1000,poll,2,1)
        bot.service.setGroupTrainingRule(admin,"time",false);bot.service.setGroupTrainingRule(admin,"guests",false)
        bot.polls.beginClose(member,"poll");bot.polls.stopped(bot.polls.get(-1,"poll"));bot.finishPollsAfterDrain()
        assertEquals(before.players,t("old").players);assertEquals(TrainingRules(false,false),t("old").rules)
        assertEquals(TrainingRules(false,false),t("poll").rules)
        assertEquals(60,t("poll").players.single().minutes)
    }
    @Test fun `old callback cannot open a hidden time editor or add guests even for an administrator`() {
        setup();bot.service.setGroupTrainingRule(admin,"time",false);bot.service.setGroupTrainingRule(admin,"guests",false);create();change(2,AttendanceChange.JOIN)
        for(action in listOf(ScreenAction("participation_time",-1,"t",user=2),ScreenAction("participation_change",-1,"t",user=2,value=1,option="ADJUST_GUESTS"))) {
            val token=bot.state.button(action,1,"test")
            val m=TgMessage(25,TgChat(1,"private"),api.bot)
            bot.handle(TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1),m,"n:$token")))
        }
        assertEquals(2,errors.size);assertTrue(errors.all { it.contains("выключен") || it.contains("выключено") })
        assertEquals(0,t().players.single().guestCount);assertEquals(60,t().players.single().minutes)
    }
    @Test fun `schema eight preserves all prior participation and settings on migration`() {
        val file=dir.resolve("old.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { s ->
            val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema-v7.sql")).bufferedReader().use { it.readText() }
            ddl.split(';').filter { it.isNotBlank() }.forEach(s::execute)
            s.execute("PRAGMA application_id=${Database.APPLICATION_ID}");s.execute("PRAGMA user_version=7")
            s.execute("INSERT INTO users(id,first_name) VALUES(1,'Игрок')")
            s.execute("INSERT INTO groups VALUES(-1,'Группа','Europe/Moscow',1)")
            s.execute("INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1)")
            s.execute("INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,created_by,created_at) VALUES(-1,'old','Теннис','2026-09-14','18:30','OPEN',1,1,'now')")
            s.execute("INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal) VALUES(-1,'old',1,1,90,90,2,350,0)")
        } }
        repeat(2) {
            val svc=SettlementService(Database(file));val t=svc.training(admin,"old")
            assertEquals(TrainingRules(),t.rules);assertEquals(TrainingRules(),svc.groupTrainingRules(-1))
            assertTrue(TrainingPolls(svc).enabled(-1))
            assertEquals(90,t.players.single().minutes);assertEquals(2,t.players.single().guestCount);assertEquals(350,t.players.single().paid)
            assertTrue(svc.database.verify().contains("Схема 8"))
        }
    }
    private fun effect(id:String="t")=calculateTraining(t(id).calculation()).entries.associate { it.participant.value.toLong() to it.amount }
    @Test fun `time toggle round trip preserves raw minutes guests and payments while recalculating equal shares`() {
        setup();create();change(2,AttendanceChange.JOIN);change(3,AttendanceChange.JOIN)
        change(2,AttendanceChange.SET_MINUTES,30);change(2,AttendanceChange.ADJUST_GUESTS,1)
        change(3,AttendanceChange.SET_MINUTES,90);change(3,AttendanceChange.SET_PAID,300)
        val before=t().players;val initial=effect();val version=t().version
        assertEquals(mapOf(2L to -120L,3L to 120L),initial)
        bot.service.setGroupTrainingRule(admin,"time",false,"off")
        assertEquals(before,t().players);assertTrue(t().version>version)
        assertEquals(mapOf(2L to -200L,3L to 200L),effect())
        assertFalse(render("public",1).richHtml!!.contains("<th>Время</th>"))
        assertFailsWith<AccountingException> { run(SettlementCommand.FinishTraining("t",version)) }
        change(3,AttendanceChange.SET_PAID,330)
        assertEquals(listOf(30L,90L),t().players.map { it.minutes });assertEquals(30,t().players.first().guestMinutes)
        val row=t().players.first()
        run(SettlementCommand.SaveAttendance("t",2,row,row.copy(paid=10)))
        assertFailsWith<AccountingException> { run(SettlementCommand.SaveAttendance("t",2,t().players.first(),t().players.first().copy(minutes=60))) }
        bot.service.setGroupTrainingRule(admin,"time",true,"on")
        assertEquals(listOf(30L,90L),t().players.map { it.minutes });assertEquals(listOf(10L,330L),t().players.map { it.paid })
        assertTrue(render("public",1).richHtml!!.contains("<th>Время</th>"))
        // A retry of the older Telegram event cannot undo the subsequent on-toggle.
        bot.service.setGroupTrainingRule(admin,"time",false,"off")
        assertTrue(t().rules.trackTime)
        assertTrue(bot.service.balances(admin).values.all { it==0L })
    }
    @Test fun `guest ban preserves existing guests permits removal and permits adding again after enable`() {
        setup();create();change(2,AttendanceChange.JOIN);change(2,AttendanceChange.SET_MINUTES,90)
        change(2,AttendanceChange.ADJUST_GUESTS,1);change(2,AttendanceChange.ADJUST_GUESTS,1)
        val before=t().players
        bot.service.setGroupTrainingRule(admin,"guests",false)
        assertEquals(before,t().players)
        assertFalse(labels(render()).any { it.contains("Добавить гостя") })
        assertTrue(labels(render()).any { it.contains("Убрать гостя") })
        assertFailsWith<AccountingException> { change(2,AttendanceChange.ADJUST_GUESTS,1) }
        change(2,AttendanceChange.SET_PAID,300);assertEquals(2,t().players.single().guestCount)
        change(2,AttendanceChange.ADJUST_GUESTS,-1)
        val row=t().players.single()
        run(SettlementCommand.SaveAttendance("t",2,row,row.copy(guestCount=0,guestMinutes=0)))
        bot.service.setGroupTrainingRule(admin,"guests",true);change(2,AttendanceChange.ADJUST_GUESTS,1)
        assertEquals(90,t().players.single().guestMinutes);assertEquals(300,t().players.single().paid)
    }
    @Test fun `zero entered durations and guests count in equal mode and return unchanged to timed mode`() {
        setup();create();change(2,AttendanceChange.JOIN);change(2,AttendanceChange.ADJUST_GUESTS,1)
        change(3,AttendanceChange.JOIN);change(3,AttendanceChange.SET_MINUTES,90);change(3,AttendanceChange.SET_PAID,300)
        bot.service.setGroupTrainingRule(admin,"time",false)
        assertEquals(mapOf(2L to -200L,3L to 200L),effect())
        val card=render("public",1)
        assertTrue(card.text.contains("Игрок 2 | 0 ₽ | -100 ₽"))
        assertTrue(card.text.contains("Игрок 2 гость 1 | 0 ₽ | -100 ₽"))
        assertEquals(0,t().players.first().minutes)
        bot.service.setGroupTrainingRule(admin,"time",true)
        assertEquals(0,t().players.first().minutes);assertEquals(0,t().players.first().guestMinutes)
        assertTrue(effect().values.all { it==0L })
    }
    @Test fun `live public and ephemeral screens refresh safely when time editor is disabled`() {
        setup();create();change(2,AttendanceChange.JOIN);change(2,AttendanceChange.SET_MINUTES,90);change(2,AttendanceChange.ADJUST_GUESTS,1)
        bot.maintain();val msg=bot.state.delivery("training:-1:t")!!.message!!
        clickOn(api.messages.getValue(-1L to msg),2,"Открыть")
        clickOn(panels.getValue(2),2,"Время · 1,5 ч");val oldTimePanel=panels.getValue(2)
        bot.service.setGroupTrainingRule(admin,"time",false);bot.service.setGroupTrainingRule(admin,"guests",false)
        bot.maintain()
        assertEquals(oldTimePanel.ephemeralId,panels.getValue(2).ephemeralId)
        assertFalse(panels.getValue(2).text!!.contains("| Время |"))
        assertFalse(panels.getValue(2).keyboard!!.rows.flatten().any { it.text.contains("Время") || it.text.contains("Добавить гостя") })
        assertTrue(panels.getValue(2).keyboard!!.rows.flatten().any { it.text.contains("Убрать гостя") })
        clickOn(oldTimePanel,2,"+0,5 ч");assertEquals(90,t().players.single().minutes)
        bot.service.setGroupTrainingRule(admin,"time",true);bot.service.setGroupTrainingRule(admin,"guests",true);bot.maintain()
        assertTrue(panels.getValue(2).keyboard!!.rows.flatten().any { it.text.contains("Время · 1,5 ч") })
        assertEquals(msg,bot.state.delivery("training:-1:t")!!.message)
    }
    @Test fun `failed setting transaction rolls back flag versions and history and restart sync is idempotent`() {
        setup();create();change(2,AttendanceChange.JOIN);change(2,AttendanceChange.SET_MINUTES,90)
        val before=t();val history=bot.service.history(admin).total
        bot.service.database.write { c -> sqlUpdate(c,"CREATE TRIGGER fail_rule BEFORE INSERT ON actions WHEN NEW.kind='UpdateTrainingRules' BEGIN SELECT RAISE(ABORT,'test'); END") }
        assertFailsWith<java.sql.SQLException> { bot.service.setGroupTrainingRule(admin,"time",false,"rollback") }
        assertEquals(before,t());assertTrue(bot.service.groupTrainingRules(-1).trackTime);assertEquals(history,bot.service.history(admin).total)
        bot.service.database.write { c -> sqlUpdate(c,"DROP TRIGGER fail_rule");sqlUpdate(c,"UPDATE groups SET track_time=0 WHERE id=-1") }
        bot=SelfServiceBot(telegram,Database(bot.service.database.path),api.bot)
        assertFalse(t().rules.trackTime);assertEquals(before.players,t().players)
        val synced=t();val syncedHistory=bot.service.history(admin).total
        bot=SelfServiceBot(telegram,Database(bot.service.database.path),api.bot)
        assertEquals(synced,t());assertEquals(syncedHistory,bot.service.history(admin).total)
    }
    @Test fun `accounted and cancelled trainings keep ledger then adopt current rules when reopened`() {
        setup();create();change(2,AttendanceChange.JOIN);change(3,AttendanceChange.JOIN)
        change(2,AttendanceChange.SET_MINUTES,30);change(3,AttendanceChange.SET_MINUTES,90);change(3,AttendanceChange.SET_PAID,400)
        run(SettlementCommand.FinishTraining("t",t().version));val closed=t();val balances=bot.service.balances(admin)
        bot.service.setGroupTrainingRule(admin,"time",false)
        assertEquals(closed,t());assertEquals(balances,bot.service.balances(admin))
        run(SettlementCommand.ReopenTraining("t",t().version));assertFalse(t().rules.trackTime)
        assertEquals(closed.players.map { it.minutes },t().players.map { it.minutes })
        run(SettlementCommand.FinishTraining("t",t().version))
        assertEquals(mapOf(2L to -200L,3L to 200L),bot.service.balances(admin))
        create("cancelled");change(2,AttendanceChange.JOIN,id="cancelled");run(SettlementCommand.CancelTraining("cancelled",t("cancelled").version))
        val cancelled=t("cancelled");bot.service.setGroupTrainingRule(admin,"time",true)
        assertEquals(cancelled,t("cancelled"));run(SettlementCommand.RestoreTraining("cancelled",t("cancelled").version))
        assertTrue(t("cancelled").rules.trackTime)
    }

    @Test fun `equal mode cannot accept changes that make restoring timed mode invalid`() {
        setup();create();change(2,AttendanceChange.JOIN);change(2,AttendanceChange.SET_MINUTES,Long.MAX_VALUE/30*30)
        bot.service.setGroupTrainingRule(admin,"time",false)
        val before=t()
        assertFailsWith<AccountingException> { change(2,AttendanceChange.ADJUST_GUESTS,1) }
        assertFailsWith<AccountingException> { run(SettlementCommand.AddPlayers("t",t().version,listOf(3))) }
        assertEquals(before,t())
        bot.service.setGroupTrainingRule(admin,"time",true)
        assertEquals(before.players,t().players)
    }

}
