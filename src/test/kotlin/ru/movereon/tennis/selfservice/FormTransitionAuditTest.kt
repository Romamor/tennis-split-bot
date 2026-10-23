package ru.movereon.tennis.selfservice

import java.nio.file.Path
import java.time.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import kotlin.test.*

/** Cross-flow invariants: navigation may ask, but must never replace or submit unfinished input. */
class FormTransitionAuditTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
        override fun requestUsers(chatId:Long,text:String,requestId:Int)=fake.send(chatId,text,null,false)
    }
    private val clock=Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"),ZoneOffset.UTC)
    private lateinit var bot:SelfServiceBot
    private var seq=1L
    private val admin=Access(-1,1,true)
    private fun run(command:SettlementCommand,a:Access=admin)=bot.service.execute(a,"fixture-${seq++}",command)
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("audit.sqlite")),fake.bot,clock)
        for(g in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            fake.members[g to fake.bot.id]=TgMember("administrator",user=fake.bot)
            for(u in 1L..3L) {
                bot.service.remember(Account(u,"Игрок $u"));bot.service.rememberMembership(g,u,true)
                fake.members[g to u]=TgMember(if(u==1L) "administrator" else "member")
            }
        }
        run(SettlementCommand.CreateTraining("open","Открытая","2026-10-23","18:30"))
        run(SettlementCommand.AddPlayers("open",1,listOf(1,2)))
        run(SettlementCommand.RecordAdminPayment("funding",2,1,100))
        run(SettlementCommand.SendOtherPayment("p",3,75))
        bot.polls.setEnabled(admin,true)
        val poll=bot.polls.create(admin,"poll","Сбор","2026-10-24","18:30","Не приду",null)
        val posted=fake.sendPoll(-1,"Сбор",poll.options(),TgKeyboard(emptyList()))
        bot.polls.attach(poll,posted.poll!!.id,posted.id)
        message("/start")
    }
    private fun latest()=bot.state.delivery("personal:1:1")?.message?.let { fake.messages[1L to it] }
        ?: fake.messages.values.last { it.chat.id==1L }
    private fun message(text:String):TgUpdate = TgUpdate(seq++,TgMessage(1000+seq,TgChat(1,"private"),TgUser(1,firstName="Игрок 1"),text)).also(bot::handle)
    private fun invoke(action:ScreenAction,source:TgMessage=latest()):TgUpdate {
        val token=bot.state.button(action,1,"personal:1:1")
        return TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1,firstName="Игрок 1"),source,"n:$token")).also(bot::handle)
    }
    private fun click(label:String,source:TgMessage=latest()):TgUpdate {
        val b=source.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        return TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1,firstName="Игрок 1"),source,b.callbackData)).also(bot::handle)
    }
    private fun dirtyCreation()=InputForm("date",0,title="Несохранённое название",date="2026-10-24",baseline="original",origin=ScreenAction("menu",0))
    private fun current(form:InputForm) {
        bot.state.session(1,1,form.group,form)
        invoke(ScreenAction("form",form.group,form.training))
    }
    private fun target(kind:String)=when(kind) {
        "pick_add_player" -> ScreenAction(kind,-1,"open",back=ScreenAction("add_player_list",-1,"open"))
        "new","new_poll","default_title","default_time","settings","training_settings","my_trainings" -> ScreenAction(kind,0)
        "edit_details","pick_add_player","pick_account","add_players","training_status","add_player_list","exclude_player_list","manage_players","participation","recover_confirm","player","ask_paid" -> ScreenAction(kind,-1,"open",user=1)
        "finance_send_confirm" -> ScreenAction(kind,-1,user=2,value=requireNotNull(bot.service.paymentDraftContext(admin,1,2).recommendation))
        "finance_payment_edit","finance_payment","finance_receive_confirm" -> ScreenAction(kind,-1,"p")
        "poll_detail","poll_close_confirm" -> ScreenAction(kind,-1,"poll")
        else -> ScreenAction(kind,-1)
    }

    @ParameterizedTest @ValueSource(strings=["new","new_poll","default_title","default_time","edit_details","finance_payment_edit","payment_new","finance_send_confirm","pick_add_player","pick_account","add_players","player","ask_paid"])
    fun `opening another form preserves dirty input until explicit discard`(kind:String) {
        setup();val original=dirtyCreation();current(original)
        val before=bot.service.balances(admin)
        invoke(target(kind))
        assertEquals(original,bot.state.form(1,1),"$kind silently replaced another form")
        assertTrue(latest().text!!.contains("несохранённые"),"$kind must offer a recoverable choice")
        click("Продолжить ввод");assertEquals(original,bot.state.form(1,1))
        invoke(target(kind));click("Сбросить и выйти")
        assertNotEquals(original,bot.state.form(1,1));assertFalse(latest().text!!.contains("несохранённые"))
        assertEquals(before,bot.service.balances(admin));assertTrue(alerts.isEmpty(),alerts.toString())
    }

    @ParameterizedTest @ValueSource(strings=["settings","training_settings","my_trainings","training_status","add_player_list","exclude_player_list","manage_players","participation","recover_confirm","finance","finance_balance_history","finance_send","finance_history","finance_payment","poll_detail","poll_close_confirm"])
    fun `navigation offers resume instead of silently clearing a dirty form`(kind:String) {
        setup();val original=dirtyCreation();current(original)
        invoke(target(kind))
        assertEquals(original,bot.state.form(1,1),"$kind discarded input")
        assertTrue(latest().text!!.contains("несохранённые"),kind)
        click("Продолжить ввод");assertEquals(original,bot.state.form(1,1))
        assertTrue(alerts.isEmpty(),alerts.toString())
    }

    @Test fun `concurrent transfer change cannot trap an edit in the error handler or block later updates`() {
        setup();invoke(ScreenAction("finance_payment_edit",-1,"p"))
        assertEquals("payment_edit",bot.state.form(1,1)!!.kind)
        run(SettlementCommand.EditPaymentAmount("p",1,100))
        val balances=bot.service.balances(admin)
        val update=message("125")
        assertTrue(bot.state.completed(update.id))
        assertNull(bot.state.form(1,1));assertTrue(latest().text!!.contains("100 ₽"))
        assertEquals(100,bot.service.transfer(admin,"p").amount)
        assertEquals(balances,bot.service.balances(admin))
        message("/start");assertEquals("Что хочешь сделать?",latest().text)
    }

    @Test fun `returning through an old form link opens the current form in its own group`() {
        setup()
        val current=InputForm("payment_ready",-2,paymentFrom=1,user=2,amount=125,origin=ScreenAction("finance",-2))
        current(current)
        invoke(ScreenAction("form",-1))
        assertEquals(current,bot.state.form(1,1));assertTrue(alerts.isEmpty(),alerts.toString())
        click("➕ 50 ₽")
        assertEquals(175,bot.state.form(1,1)!!.amount)
        assertEquals(-2,bot.state.form(1,1)!!.group)
    }

    @Test fun `resuming an edit after a concurrent change opens a readable current card`() {
        setup();invoke(ScreenAction("finance_payment_edit",-1,"p"));message("/start")
        run(SettlementCommand.EditPaymentAmount("p",1,100))
        click("Продолжить ввод")
        assertNull(bot.state.form(1,1));assertTrue(latest().text!!.contains("100 ₽"))
        assertTrue(alerts.isEmpty(),alerts.toString())
        assertTrue(bot.service.database.verify().contains("целостность в порядке"))
    }

    @Test fun `closed training cannot trap player selection in repeated error rendering`() {
        setup()
        val selection=InputForm("add_players",-1,"open",selectedUsers=listOf(3),order=listOf(3),origin=ScreenAction("training",-1,"open"))
        bot.state.session(1,1,-1,selection)
        run(SettlementCommand.CancelTraining("open",bot.service.training(admin,"open").version))
        val update=message("текст вместо кнопки")
        assertTrue(bot.state.completed(update.id));assertNull(bot.state.form(1,1))
        assertTrue(latest().text!!.contains("Отменена"));message("/start")
        assertEquals("Что хочешь сделать?",latest().text)
    }

    private fun sample(kind:String):InputForm = when(kind) {
        "payment_from","payment_to","payment_amount","payment_ready","payment_duplicate","payment_edit" -> InputForm(kind,-1,user=3,paymentFrom=1,
            amount=125,adminPayment=true,transfer="p",version=1,similar=listOf("p"),origin=ScreenAction("finance",-1))
        "pick_account","pick_players","pick_add_player","paid","add_players" -> InputForm(kind,-1,"open",user=1,
            request=42,selectedUsers=if(kind=="add_players") listOf(3) else emptyList(),order=listOf(3),origin=ScreenAction("training",-1,"open"))
        else -> InputForm(kind,0,title="Новый ввод",date="2026-10-24",time="18:30",baseline="original",
            defaultValue=if(kind=="default_time") "18:30" else "Теннис",publishGroup=-1,
            pollId=if(kind in setOf("poll_decline","poll_photo")) "draft-poll" else "",origin=ScreenAction("menu",0))
    }

    @ParameterizedTest @ValueSource(strings=["title","date","time","group","ready","poll_decline","poll_photo","default_title","default_time","payment_from","payment_to","payment_amount","payment_ready","payment_duplicate","payment_edit","pick_account","pick_players","pick_add_player","paid","add_players"])
    fun `rendering every form preserves stored fields balances and valid signatures`(kind:String) {
        setup();val f=sample(kind);bot.state.session(1,1,f.group,f)
        val balance=bot.service.balances(admin);val history=bot.service.history(admin).total
        val options=listOf(GroupOption(bot.service.group(-1),true,true,true),GroupOption(bot.service.group(-2),true,true,true))
        val screen=ScreenAction(if(kind=="add_players") kind else "form",f.group,f.training)
        val out=bot.screens.render(screen,if(f.group<0) admin else null,"personal:1:1",1,f,groupOptions=options)
        assertEquals(f,bot.state.form(1,1));assertEquals(balance,bot.service.balances(admin))
        assertEquals(history,bot.service.history(admin).total)
        val signature=bot.state.formSignature(f)
        out.tokens.mapNotNull(bot.state::button).map { it.action }.filter { it.option.matches(Regex("[a-f0-9]{24}")) }
            .forEach { assertEquals(signature,it.option,"${it.kind} does not match the rendered form") }
    }

    @ParameterizedTest @ValueSource(strings=["title","date","time","default_title","default_time","payment_ready","payment_edit","paid"])
    fun `invalid text preserves the current form and can always be cancelled`(kind:String) {
        setup();val base=sample(kind);val f=if(kind=="payment_edit") base.copy(amount=75) else base
        current(if(kind=="payment_ready") f.copy(waitingForAmount=true) else f)
        val before=bot.state.form(1,1);val balance=bot.service.balances(admin)
        val update=message(if(kind in setOf("title","default_title")) "x".repeat(101) else "неверный формат")
        assertTrue(bot.state.completed(update.id));assertEquals(before,bot.state.form(1,1))
        assertEquals(balance,bot.service.balances(admin))
        message("/start")
        if(latest().text!!.contains("несохранённые")) click("Сбросить и выйти")
        assertNull(bot.state.form(1,1));assertEquals("Что хочешь сделать?",latest().text)
    }

    @Test fun `an old return link with no remaining form returns to the menu`() {
        setup();invoke(ScreenAction("form",-1))
        assertEquals("Что хочешь сделать?",latest().text);assertTrue(alerts.isEmpty(),alerts.toString())
    }

    @ParameterizedTest @ValueSource(strings=["participation_change","finance_receive_save","finance_payment_cancel_save","set_training_status","group_rule_save","poll_close"])
    fun `unrelated mutations never discard or submit a different dirty form`(kind:String) {
        setup();run(SettlementCommand.SendOtherPayment("incoming",1,50),Access(-1,2))
        val original=dirtyCreation();current(original)
        val training=bot.service.training(admin,"open");val balance=bot.service.balances(admin)
        val transfer=bot.service.transfer(admin,"p");val incoming=bot.service.transfer(admin,"incoming")
        val rules=bot.service.groupTrainingRules(-1)
        val action=when(kind) {
            "participation_change" -> ScreenAction(kind,-1,"open",user=1,option=AttendanceChange.ADJUST_PAID.name,value=50)
            "finance_receive_save" -> ScreenAction(kind,-1,"incoming",version=incoming.version)
            "finance_payment_cancel_save" -> ScreenAction(kind,-1,"p",version=transfer.version)
            "set_training_status" -> ScreenAction(kind,-1,"open",version=training.version,option=TrainingPhase.CANCELLED.name)
            "group_rule_save" -> ScreenAction(kind,-1,option="guests",value=0)
            else -> ScreenAction(kind,-1,"poll")
        }
        invoke(action)
        assertEquals(original,bot.state.form(1,1));assertEquals(balance,bot.service.balances(admin))
        assertEquals(training,bot.service.training(admin,"open"));assertEquals(transfer,bot.service.transfer(admin,"p"))
        assertEquals(incoming,bot.service.transfer(admin,"incoming"));assertEquals(rules,bot.service.groupTrainingRules(-1))
        assertEquals("OPEN",bot.polls.get(-1,"poll").status)
        assertTrue(alerts.last().contains("ввод"))
        message("/start");click("Сбросить и выйти")
        invoke(ScreenAction("player",-1,"open",user=1))
        invoke(ScreenAction("change",-1,"open",user=1,option=AttendanceChange.SET_PAID.name,value=125))
        val draft=bot.state.attendanceDraft(1,-1)
        invoke(action)
        assertEquals(draft,bot.state.attendanceDraft(1,-1))
        assertEquals(training,bot.service.training(admin,"open"));assertEquals(balance,bot.service.balances(admin))
        assertEquals(incoming,bot.service.transfer(admin,"incoming"));assertEquals(rules,bot.service.groupTrainingRules(-1))
        assertEquals("OPEN",bot.polls.get(-1,"poll").status)
        assertTrue(alerts.last().contains("ввод"))
    }

    @Test fun `discarding one attendance draft can open another without losing the replacement`() {
        setup()
        val other=Access(-2,1,true)
        run(SettlementCommand.CreateTraining("other","Другая","2026-10-24","18:30"),other)
        run(SettlementCommand.AddPlayers("other",1,listOf(1,2)),other)
        invoke(ScreenAction("player",-1,"open",user=1))
        invoke(ScreenAction("change",-1,"open",user=1,option=AttendanceChange.SET_PAID.name,value=125))
        val draft=bot.state.attendanceDraft(1,-1)!!;val balances=bot.service.balances(admin)
        invoke(ScreenAction("player",-2,"other",user=1))
        assertEquals(draft,bot.state.attendanceDraft(1,-1))
        click("Сбросить и выйти")
        assertNull(bot.state.attendanceDraft(1,-1))
        assertEquals("other",bot.state.attendanceDraft(1,-2)!!.training)
        assertEquals(balances,bot.service.balances(admin));assertTrue(alerts.isEmpty(),alerts.toString())
    }

    @ParameterizedTest @ValueSource(strings=["title","date","time","ready"])
    fun `editing a changed training recovers to its current card without overwriting it`(kind:String) {
        setup();val before=bot.service.training(admin,"open")
        current(InputForm(kind,-1,"open",version=before.version,title=before.title,date=before.date,time=before.startTime,
            baseline="original",origin=ScreenAction("training",-1,"open")))
        run(SettlementCommand.EditTraining("open",before.version,"Изменено другим участником",before.date,before.startTime))
        val update=message("Мой новый ввод")
        assertTrue(bot.state.completed(update.id));assertNull(bot.state.form(1,1))
        assertTrue(latest().text!!.contains("Изменено другим участником"))
        assertEquals("Изменено другим участником",bot.service.training(admin,"open").title)
    }

    @ParameterizedTest @ValueSource(strings=["default_title","default_time"])
    fun `changed defaults reopen their settings instead of a permanently stale form`(kind:String) {
        setup();current(sample(kind))
        val update=if(kind=="default_title") DefaultTrainingUpdate("title","Теннис","Другой стандарт") else DefaultTrainingUpdate("time","18:30","19:30")
        bot.service.updateTrainingDefaults(1,update)
        invoke(ScreenAction("form",0))
        assertNull(bot.state.form(1,1));assertTrue(latest().text!!.contains("параметры тренировки"))
        assertEquals(update.value,if(kind=="default_title") bot.service.trainingDefaults(1).title else bot.service.trainingDefaults(1).time)
    }

    @Test fun `deferred navigation rechecks permissions before opening another edit`() {
        setup();run(SettlementCommand.SendOtherPayment("foreign",3,100),Access(-1,2))
        val input=dirtyCreation();current(input)
        invoke(ScreenAction("finance_payment_edit",-1,"foreign"))
        fake.members[-1L to 1L]=TgMember("member")
        click("Сбросить и выйти")
        assertEquals(input,bot.state.form(1,1));assertTrue(alerts.isNotEmpty())
        click("Продолжить ввод");assertEquals(input,bot.state.form(1,1))
    }

    @Test fun `an old reload button cannot replace another player's attendance draft`() {
        setup();invoke(ScreenAction("player",-1,"open",user=1))
        invoke(ScreenAction("change",-1,"open",user=1,option=AttendanceChange.SET_PAID.name,value=125))
        val draft=bot.state.attendanceDraft(1,-1)
        invoke(ScreenAction("reload_attendance",-1,"open",user=2))
        assertEquals(draft,bot.state.attendanceDraft(1,-1));assertTrue(alerts.last().contains("ввод"))
    }

    @Test fun `a group rejected by Telegram cannot make start silently disappear`() {
        setup();current(sample("payment_ready"));val balance=bot.service.balances(admin)
        fake.memberRejected=true
        val update=message("/start")
        assertTrue(bot.state.completed(update.id));assertNull(bot.state.form(1,1))
        assertTrue(latest().text!!.contains("Группа недоступна"))
        click("Назад");assertEquals("Что хочешь сделать?",latest().text)
        assertEquals(balance,bot.service.balances(admin))
    }

    @Test fun `replaying a prepared draft switch preserves newer input in the destination group`() {
        setup();invoke(ScreenAction("player",-1,"open",user=1))
        invoke(ScreenAction("change",-1,"open",user=1,option=AttendanceChange.SET_PAID.name,value=125))
        val old=bot.state.attendanceDraft(1,-1)!!
        val other=Access(-2,1,true)
        run(SettlementCommand.CreateTraining("other","Другая","2026-10-24","18:30"),other)
        run(SettlementCommand.AddPlayers("other",1,listOf(1)),other)
        val value=bot.service.training(other,"other").players.single()
        val replacement=AttendanceDraft("other",1,value,value)
        val newer=replacement.copy(value=replacement.value.copy(paid=300))
        bot.state.attendanceDraft(1,-2,newer)
        val update=TgUpdate(seq++,TgMessage(1000+seq,TgChat(1,"private"),TgUser(1,firstName="Игрок 1"),"/start"))
        bot.state.plan(update.id,EventPlan(1,1,ScreenAction("player",-2,"other",user=1),
            draft=old,clearDraft=true,clearDraftGroup=-1,nextDraft=replacement))
        bot.handle(update)
        assertTrue(bot.state.completed(update.id))
        assertEquals(old,bot.state.attendanceDraft(1,-1));assertEquals(newer,bot.state.attendanceDraft(1,-2))
    }
}
