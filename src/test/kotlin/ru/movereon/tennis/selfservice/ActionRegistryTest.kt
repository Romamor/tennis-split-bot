package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.core.ErrorCode
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.storage.sqlQuery
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ActionRegistryTest {
    @TempDir lateinit var dir:Path
    private val clock=Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"),ZoneOffset.UTC)
    private val fake=FakeTelegramApi()
    private val answers=mutableListOf<String>()
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { text?.let(answers::add) }
    }
    private val access=Access(-1,1,true)
    private lateinit var bot:SelfServiceBot
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("actions.sqlite")),fake.bot,clock)
        bot.service.register(SettlementGroup(-1,"Тестовая группа","Europe/Moscow"))
        fake.members[-1L to fake.bot.id]=TgMember("administrator",user=fake.bot)
        for(user in 1L..3) {
            bot.service.remember(Account(user,"Участник $user"))
            bot.service.rememberMembership(-1,user,true)
            fake.members[-1L to user]=TgMember(if(user==1L) "administrator" else "member",user=TgUser(user,firstName="Участник $user"))
        }
    }
    private fun callback(kind:String,id:String="",event:Long=100) {
        val token=bot.state.button(ScreenAction(kind,-1,id),1,"registry-test",true)
        val message=TgMessage(500,TgChat(1,"private"),fake.bot,"Кнопка")
        bot.handle(TgUpdate(event,callback=TgCallback("callback-$event",TgUser(1,firstName="Участник 1"),message,"n:$token")))
    }

    @Test fun `unknown generated buttons and persisted callbacks are rejected without losing input`() {
        setup()
        val layout=ScreenLayout(ScreenAction("menu",0),bot.state,fake.bot.username!!,"unknown",1,false)
        val failure=assertFailsWith<AccountingException> { layout.button("Кнопка",ScreenAction("unsupported_action",0)) }
        assertEquals(ErrorCode.INVALID_INPUT,failure.code)
        assertFailsWith<AccountingException> { bot.screens.render(ScreenAction("unsupported_action",0),null,"unknown",1) }
        val form=InputForm("payment_ready",-1,user=2,paymentFrom=1,amount=300)
        bot.state.session(1,1,-1,form)
        callback("unsupported_action")
        assertTrue(bot.state.completed(100))
        assertEquals(form,bot.state.form(1,1))
        assertTrue(answers.last().contains("Кнопка больше не поддерживается"))
    }

    @Test fun `buttons emitted by main screens and current and legacy forms have registered routes`() {
        setup()
        fun execute(key:String,command:SettlementCommand)=bot.service.execute(access,key,command)
        execute("create",SettlementCommand.CreateTraining("training","Тестовая тренировка","2026-09-14","18:30"))
        execute("join",SettlementCommand.ChangeAttendance("training",1,AttendanceChange.JOIN))
        execute("time",SettlementCommand.ChangeAttendance("training",1,AttendanceChange.SET_MINUTES,60))
        execute("paid",SettlementCommand.ChangeAttendance("training",1,AttendanceChange.SET_PAID,300))
        execute("payment",SettlementCommand.RecordAdminPayment("payment",1,2,100))
        bot.service.execute(Access(-1,2),"incoming",SettlementCommand.SendOtherPayment("incoming",1,50))
        bot.polls.setEnabled(access,true)
        val poll=bot.polls.create(access,"poll","Тестовый опрос","2026-09-14","18:30","Не приду")
        bot.polls.attach(poll,"telegram-poll",600)
        val groupOptions=listOf(GroupOption(bot.service.group(-1),true,true,true))
        val rendered=mutableSetOf<String>()
        fun render(action:ScreenAction,form:InputForm?=null) {
            bot.screens.render(action,if(action.group<0) access else null,"registry-${rendered.size}",1,form,
                telegramAdmins=setOf(1),groupOptions=groupOptions)
            rendered+=action.kind
        }
        for(kind in listOf("menu","groups","settings","training_settings","my_trainings")) render(ScreenAction(kind,0))
        for(kind in listOf("poll_settings","finance","finance_balance_history","finance_member_history","finance_balance_members","finance_send",
            "finance_receive","finance_received","finance_balances","finance_history","finance_group_history","own_group_trainings",
            "trainings","administrators","admin_candidates","admin_person")) render(ScreenAction(kind,-1,user=2))
        for(kind in listOf("training","public","my_training","training_status","add_player_list","exclude_player_list","manage_players",
            "participation","participation_time","participation_payment","player","leave_confirm","roster","history","preview_finish","cancel_confirm","recover_confirm"))
            render(ScreenAction(kind,-1,"training",user=1))
        for(kind in listOf("finance_payment","finance_payment_cancel_confirm")) render(ScreenAction(kind,-1,"payment"))
        render(ScreenAction("finance_receive_confirm",-1,"incoming"))
        for(kind in listOf("poll_list","poll_detail","poll_close_confirm","poll_discard_confirm")) render(ScreenAction(kind,-1,"poll"))
        bot.polls.beginClose(access,"poll")
        bot.polls.closeFailed(bot.polls.get(-1,"poll"),true)
        render(ScreenAction("poll_close_recover_confirm",-1,"poll"))
        for(kind in listOf("title","date","time","default_title","default_time","group","ready","poll_decline","poll_photo")) {
            val form=InputForm(kind,0,date="2026-09-14",publishGroup=-1,pollId="new-poll",pollPhotoId="test-photo")
            render(ScreenAction("form",0),form)
        }
        for(kind in listOf("payment_from","payment_to","payment_amount","payment_ready","payment_duplicate","payment_edit")) {
            val form=InputForm(kind,-1,user=2,paymentFrom=1,amount=100,adminPayment=true,transfer="payment",
                version=bot.service.transfer(access,"payment").version,similar=listOf("payment"),roundingAvailable=true)
            render(ScreenAction("form",-1),form)
        }
        for(kind in listOf("paid","pick_account","pick_players","pick_add_player"))
            render(ScreenAction("form",-1,"training"),InputForm(kind,-1,"training",user=1,origin=ScreenAction("training",-1,"training")))
        render(ScreenAction("add_players",-1,"training"),InputForm("add_players",-1,"training",selectedUsers=listOf(2),order=listOf(2,3)))
        render(ScreenAction("exit_confirm",-1,back=ScreenAction("menu",0),resume=ScreenAction("form",-1)))
        render(ScreenAction("profile_preview",-1,user=2,resume=ScreenAction("payment_new",-1,user=2)))

        val screenRoutes=setOf(ActionRoute.NAVIGATION_SCREEN,ActionRoute.TRAINING_SCREEN,ActionRoute.FINANCE_SCREEN,ActionRoute.POLL_SCREEN,ActionRoute.FORMER_MEMBER_SCREEN,ActionRoute.SCREEN)
        val registeredScreens=ActionRegistry.all.filter { it.route in screenRoutes }.map { it.kind }.toSet()
        // These two rendered inputs first prepare their drafts in the bot, so their route is BOT.
        assertEquals(registeredScreens+setOf("player","add_players"),rendered)
        val emitted=bot.state.database.read { c -> sqlQuery(c,"SELECT action_json FROM bot_buttons") {
            bot.state.json.decodeFromString<ScreenAction>(it.getString(1))
        } }
        assertTrue(emitted.size>100)
        emitted.forEach { action -> assertEquals(action.kind,ActionRegistry.requireKnown(action.kind).kind) }
        assertTrue(emitted.any { it.kind=="poll_close_recover" })
        assertTrue(emitted.any { it.kind=="save_attendance" || it.kind=="change" })
    }

    @Test fun `old draft continuations keep their group and form guards`() {
        val payment=InputForm("payment_amount",-1,user=2,paymentFrom=1,amount=300)
        assertTrue(InputNavigation.continues(ScreenAction("payment_new",-1),payment,null,-1))
        assertFalse(InputNavigation.continues(ScreenAction("payment_new",-2),payment,null,-1))
        assertFalse(InputNavigation.continues(ScreenAction("payment_new",-1,option="admin"),payment,null,-1))
        val draft=AttendanceDraft("training",1,null,Attendance(1,true,minutes=30))
        assertTrue(InputNavigation.continues(ScreenAction("player",-1,"training",user=1),InputForm("paid",-1,"training"),draft,-1))
        assertFalse(InputNavigation.continues(ScreenAction("player",-1,"training",user=1),payment,draft,-1))
        assertFalse(InputNavigation.continues(ScreenAction("ask_paid",-1,"other",user=1),null,draft,-1))
        assertTrue(InputNavigation.continues(ScreenAction("add_players",-1,"training"),InputForm("pick_players",-1,"training"),null,-1))
        assertFalse(InputNavigation.continues(ScreenAction("add_players",-1,"other"),InputForm("pick_players",-1,"training"),null,-1))
    }

    @Test fun `persisted retired finance actions and forms redirect without executing old payments`() {
        setup()
        for((index,kind) in listOf("transfer_amount","form").withIndex()) {
            bot.state.session(1,1,-1,InputForm("transfer_amount",-1,user=2,amount=300))
            callback(kind,event=100L+index)
            assertNull(bot.state.form(1,1))
            assertTrue(fake.messages.values.last { it.chat.id==1L }.text!!.startsWith("Мои финансы"))
            assertEquals(0,bot.service.financePayments(access,0,allGroup=true).total)
        }
    }

    @Test fun `recovering a stopped poll cannot bypass unsaved input`() {
        setup()
        bot.polls.setEnabled(access,true)
        val poll=bot.polls.create(access,"poll","Тестовый опрос","2026-09-14","18:30","Не приду")
        bot.polls.attach(poll,"telegram-poll",600)
        bot.polls.beginClose(access,"poll")
        bot.polls.closeFailed(bot.polls.get(-1,"poll"),true)
        val form=InputForm("payment_ready",-1,user=2,paymentFrom=1,amount=300)
        bot.state.session(1,1,-1,form)
        callback("poll_close_recover","poll")
        assertEquals("FAILED",bot.polls.get(-1,"poll").status)
        assertEquals(form,bot.state.form(1,1))
        assertTrue(answers.last().contains("Сначала заверши или отмени текущий ввод"))
        assertTrue(bot.service.trainings(access).items.isEmpty())
    }
}
