package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

/** Real navigation and forged/replayed callbacks share the same live membership boundary. */
class FormerMemberFlowTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
    }
    private val clock=object:Clock() {
        var now=Instant.parse("2026-10-28T09:00:00Z")
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=fixed(now,zone)
    }
    private lateinit var bot:SelfServiceBot
    private var seq=1L
    private val viewer=2L
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("former.sqlite")),fake.bot,clock)
        for(g in -2L..-1L) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..3L) {
                bot.service.remember(Account(u,"Игрок $u"))
                bot.service.rememberMembership(g,u,true)
                fake.members[g to u]=TgMember("member")
            }
            fake.members[g to fake.bot.id]=TgMember("administrator")
        }
        seed("own",listOf(1,2),300)
        seed("other",listOf(1,3),800)
    }
    private fun run(c:SettlementCommand,g:Long = -1)=bot.service.execute(Access(g,1,true),"seed${seq++}",c)
    private fun seed(id:String,players:List<Long>,paid:Long,g:Long = -1) {
        run(SettlementCommand.CreateTraining(id,id,"2026-10-27","18:30"),g)
        run(SettlementCommand.AddPlayers(id,1,players),g)
        players.forEach { run(SettlementCommand.ChangeAttendance(id,it,AttendanceChange.ADJUST_MINUTES,60),g) }
        run(SettlementCommand.ChangeAttendance(id,players.first(),AttendanceChange.SET_PAID,paid),g)
        run(SettlementCommand.FinishTraining(id,bot.service.training(Access(g,1),id).version),g)
    }
    private fun leave(g:Long = -1) {
        fake.members[g to viewer]=TgMember("left")
        bot.service.rememberMembership(g,viewer,false)
        clock.now=clock.now.plusSeconds(31)
    }
    private fun latest()=fake.messages.values.last { it.chat.id==viewer }
    private fun html()=fake.richMessages.getValue(viewer to latest().id)
    private fun message(text:String) {
        bot.handle(TgUpdate(seq++,TgMessage(seq,TgChat(viewer,"private"),TgUser(viewer,firstName="Игрок 2"),text)))
    }
    private fun callback(data:String,source:TgMessage=latest()) {
        bot.handle(TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(viewer,firstName="Игрок 2"),source,data)))
    }
    private fun click(text:String) {
        callback(requireNotNull(latest().keyboard!!.rows.flatten().single { it.text==text || it.text.endsWith(" $text") }.callbackData))
    }
    private fun tokens()=Regex("data=\"([^\"]+)\"").findAll(html()).map { it.groupValues[1] }.distinct().toList()
    private fun follow(kind:String,id:String?=null) {
        callback(tokens().first { bot.state.button(it.removePrefix("n:"))!!.action.let { a -> a.kind==kind && (id==null || a.id==id) } })
    }
    private fun openFinance() { message("/start");click("Мои финансы");click("Группа 1") }
    private fun forge(action:ScreenAction,chat:Long=viewer) {
        val data="n:"+bot.state.button(action,viewer,"probe${seq++}",permanent=true)
        val source=if(chat==viewer) latest() else TgMessage(seq++,TgChat(chat,"supergroup"),fake.bot,"Карточка")
        callback(data,source)
    }
    @Test fun `former member sees own balance and complete history with no transfer or participant links`() {
        setup()
        run(SettlementCommand.RecordAdminPayment("payment",2,1,50))
        val before=bot.service.history(Access(-1,1)).total
        leave();openFinance()
        assertTrue(latest().text!!.contains("−100 ₽"))
        assertEquals(listOf("📜 История баланса","🏓 Мои тренировки","⬅️ Назад"),latest().keyboard!!.rows.flatten().map { it.text })
        click("История баланса")
        assertTrue(latest().text!!.contains("−150 ₽"))
        assertTrue(latest().text!!.contains("+50 ₽"))
        assertFalse(html().contains("Игрок 1"));assertFalse(html().contains("other"))
        assertEquals(setOf("my_training"),tokens().map { bot.state.button(it.removePrefix("n:"))!!.action.kind }.toSet())
        val balance=bot.service.balanceHistory(Access(-1,viewer))
        assertEquals(balance.balance.toBigInteger(),balance.page.items.sumOf { it.amount })
        follow("my_training","own")
        assertTrue(latest().text!!.contains("Игрок 2"));assertFalse(latest().text!!.contains("Игрок 1"))
        assertEquals(listOf("⬅️ Назад"),latest().keyboard!!.rows.flatten().map { it.text })
        click("Назад");assertTrue(latest().text!!.startsWith("История баланса"))
        assertEquals(before,bot.service.history(Access(-1,1)).total)
    }
    @Test fun `only participated trainings open and forged training or other member history is denied`() {
        setup();leave();openFinance();click("Мои тренировки")
        assertTrue(html().contains("own"));assertFalse(html().contains("other"))
        follow("my_training","own");click("Назад")
        forge(ScreenAction("my_training",-1,"other"));assertTrue(alerts.last().contains("своих тренировок"))
        val denied=listOf("finance_send","finance_receive","finance_history","finance_group_history","finance_balances","finance_balance_members","finance_member_history","finance_payment","training","edit_training","participation","participation_time","participation_payment","administrators","poll_settings","history","roster")
        for(kind in denied) {
            val n=alerts.size
            forge(ScreenAction(kind,-1,"own",user=1))
            assertEquals(n+1,alerts.size,kind);assertTrue(alerts.last().contains("участникам"),kind)
        }
        forge(ScreenAction("finance_balance_history",-2,user=1))
        assertFalse(latest().text!!.contains("−150 ₽")) // Subject stays the real viewer in the other group.
        assertTrue(latest().text!!.contains("Сейчас: 0 ₽"))
    }
    @Test fun `old finance and editor buttons cannot bypass departure including appointed administrators`() {
        setup();run(SettlementCommand.SetAdministrator(2,true));openFinance()
        val old=latest()
        val send=old.keyboard!!.rows.flatten().single { it.text.endsWith("Перевести") }.callbackData!!
        leave();callback(send,old);assertTrue(alerts.last().contains("участникам"))
        forge(ScreenAction("finance",-1))
        assertFalse(latest().text!!.contains("ждёт подтверждения"))
        assertFalse(latest().keyboard!!.rows.flatten().any { it.text.contains("за участников") })
        assertFailsWith<AccountingException> { bot.service.execute(Access(-1,2,true),"forged-admin",SettlementCommand.RecordAdminPayment("bad",1,3,500)) }
        forge(ScreenAction("finance_send_save",-1,user=1,value=150))
        assertTrue(alerts.last().contains("участникам"))
        forge(ScreenAction("finance",-1),chat=-1)
        assertTrue(alerts.last().contains("участникам"))
    }
    @Test fun `unknown group cannot be discovered through forged archive and returning member regains ordinary interface`() {
        setup();leave();openFinance()
        bot.service.register(SettlementGroup(-3,"Посторонняя группа","UTC"))
        forge(ScreenAction("finance",-3));assertTrue(alerts.last().contains("не найден"))
        fake.members[-1L to viewer]=TgMember("member")
        forge(ScreenAction("finance",-1))
        assertTrue(latest().keyboard!!.rows.flatten().any { it.text.endsWith("Перевести") })
        follow("finance_balance_history");follow("my_training","own")
        assertTrue(latest().text!!.contains("Игрок 1"))
    }
    @Test fun `global training list excludes creator only open training after departure and permits own old cards`() {
        setup()
        bot.service.execute(Access(-1,viewer),"creator",SettlementCommand.CreateTraining("creator-only","creator-only","2026-10-28","18:30"))
        leave();message("/start");click("Мои тренировки")
        assertTrue(html().contains("own"));assertFalse(html().contains("creator-only"))
        follow("my_training","own");assertFalse(latest().text!!.contains("Игрок 1"))
        click("Назад");assertTrue(latest().text!!.startsWith("Тренировок:"))
        forge(ScreenAction("my_training",-1,"creator-only"));assertTrue(alerts.last().contains("своих тренировок"))
    }
    @Test fun `former groups are selectable only for finance and single former group skips picker`() {
        setup();leave(-1);leave(-2)
        message("/start");click("Мои финансы")
        assertEquals(setOf("👥 Группа 1","👥 Группа 2","⬅️ Назад"),latest().keyboard!!.rows.flatten().map { it.text }.toSet())
        val other=GroupOption(SettlementGroup(-1,"Группа","UTC"),false,false,false,present=false)
        for(section in listOf("manage","administrators","poll_settings","polls")) assertTrue(GroupSelection.choices(listOf(other),section).isEmpty(),section)
        assertEquals(listOf(other),GroupSelection.choices(listOf(other),"finance"))
        val db=Database(dir.resolve("single.sqlite"));val second=SelfServiceBot(api,db,fake.bot,clock)
        second.service.register(other.group);second.service.remember(Account(viewer,"Игрок 2"));second.service.rememberMembership(-1,viewer,false)
        bot=second
        message("/start");click("Мои финансы")
        assertTrue(latest().text!!.startsWith("Мои финансы:"))
        assertTrue(latest().text!!.contains("Нулевой баланс"))
    }
    @Test fun `all history pages reconcile after corrections cancellations and pending transfers`() {
        setup()
        for(i in 1..17) seed("extra-$i",listOf(1,2),300)
        run(SettlementCommand.RecordAdminPayment("settled",2,1,100))
        bot.service.execute(Access(-1,viewer),"pending",SettlementCommand.SendOtherPayment("pending",1,999))
        leave()
        run(SettlementCommand.EditPaymentAmount("settled",1,200))
        run(SettlementCommand.ReopenTraining("extra-1",bot.service.training(Access(-1,1),"extra-1").version))
        val all=(0..1).map { bot.service.balanceHistory(Access(-1,viewer),it) }
        assertEquals(-2350L,bot.service.ownBalance(Access(-1,viewer)))
        assertEquals(18,all.first().page.total)
        assertEquals(listOf(15,3),all.map { it.page.items.size })
        assertEquals(all.first().balance.toBigInteger(),all.flatMap { it.page.items }.sumOf { it.amount })
        assertTrue(all.flatMap { it.page.items }.none { it.transferId=="pending" || it.trainingId=="extra-1" })
        openFinance();click("История баланса")
        click("Дальше ›");assertTrue(latest().text!!.contains("История баланса"))
        follow("my_training");click("Назад")
        assertEquals(1,tokens().first().let { bot.state.button(it.removePrefix("n:"))!!.action.back!!.page })
    }
    @Test fun `departure discards unsavable input without trapping start or posting money`() {
        setup();openFinance();click("Перевести");click("Записать свой перевод")
        val target=tokens().first { bot.state.button(it.removePrefix("n:"))!!.action.user==1L }
        callback(target);click("Ввести сумму сообщением");message("500")
        assertNotNull(bot.state.form(viewer,viewer))
        val count=bot.service.history(Access(-1,1)).total
        leave();message("/start")
        assertNull(bot.state.form(viewer,viewer));assertTrue(latest().text!!.contains("Что хочешь сделать?"))
        assertTrue(latest().text!!.contains("Изменения не сохранены"))
        click("Мои финансы");click("Группа 1")
        assertTrue(latest().text!!.contains("−150 ₽"))
        assertEquals(count,bot.service.history(Access(-1,1)).total)
    }

}
