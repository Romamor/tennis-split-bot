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
    private val telegram=object:TelegramApi by api {
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
        val m=latest(u);val b=m.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        bot.handle(TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(u,firstName="Игрок $u"),m,b.callbackData)))
    }
    @Test fun `defaults retain guests and individual time and settings are admin only and group scoped`() {
        setup();assertEquals(TrainingRules(),bot.service.groupTrainingRules(-1))
        message("/start");click("Настройки");click("Настройки групп");click("Группа 1")
        assertTrue(latest().text!!.contains("новых тренировок"))
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
        run(SettlementCommand.ReopenTraining("t",t().version));assertFalse(t().rules.trackTime);assertTrue(t().rules.guestsEnabled)
        create("new");assertEquals(TrainingRules(false,true),t("new").rules)
    }
    @Test fun `open existing training keeps its rules and new poll training uses current defaults`() {
        setup();create("old");change(2,AttendanceChange.JOIN,id="old");change(2,AttendanceChange.SET_MINUTES,90,id="old");change(2,AttendanceChange.ADJUST_GUESTS,1,id="old")
        val before=t("old")
        bot.polls.setEnabled(admin,true)
        val poll=bot.polls.create(member,"poll","Теннис","2026-09-14","18:30","Не приду")
        bot.polls.attach(poll,"telegram-poll",100)
        bot.polls.vote(1000,poll,2,1)
        bot.service.setGroupTrainingRule(admin,"time",false);bot.service.setGroupTrainingRule(admin,"guests",false)
        bot.polls.beginClose(member,"poll");bot.polls.stopped(bot.polls.get(-1,"poll"));bot.finishPollsAfterDrain()
        assertEquals(before,t("old"))
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
}
