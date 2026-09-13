package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class FinanceFlowTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
    }
    private lateinit var bot:SelfServiceBot
    private var seq=1L
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite")),fake.bot,Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"),ZoneOffset.UTC))
        for(g in -2L..-1L) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..12L) { bot.service.remember(Account(u,"Игрок $u"));bot.service.rememberMembership(g,u,true);fake.members[g to u]=TgMember("member") }
            fake.members[g to fake.bot.id]=TgMember("administrator",user=fake.bot)
        }
    }
    private fun run(c:SettlementCommand,a:Access=Access(-1,1))=bot.service.execute(a,"seed${seq++}",c)
    private fun latest(u:Long)=fake.messages.values.last { it.chat.id==u }
    private fun rows(u:Long)=latest(u).keyboard!!.rows.map { row->row.map { it.text } }
    private fun message(u:Long,text:String) { bot.handle(TgUpdate(seq++,TgMessage(seq,TgChat(u,"private"),TgUser(u,firstName="Игрок $u"),text))) }
    private fun click(u:Long,text:String,source:TgMessage=latest(u)):TgUpdate {
        val button=source.keyboard!!.rows.flatten().single { it.text==text }
        return TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(u,firstName="Игрок $u"),source,button.callbackData)).also(bot::handle)
    }
    private fun open(u:Long,group:String="Группа 1") { message(u,"/start");click(u,"💰 Мои финансы");click(u,group) }
    private fun seedBalance() {
        run(SettlementCommand.CreateTraining("t","Теннис","2026-09-13","18:30"))
        run(SettlementCommand.AddPlayers("t",1,listOf(1,2)))
        for(u in 1L..2L) run(SettlementCommand.ChangeAttendance("t",u,AttendanceChange.ADJUST_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300))
        run(SettlementCommand.FinishTraining("t",bot.service.training(Access(-1,1),"t").version))
    }
    @Test fun `send and receive screens follow the diagram and return to the right menus`() {
        setup();seedBalance();open(2)
        assertEquals("Мои финансы:",latest(2).text)
        assertEquals(listOf(listOf("Отправить платеж","Принять платеж"),listOf("История платежей"),listOf("Должники"),listOf("Назад")),rows(2))
        click(2,"Отправить платеж");click(2,"Игрок 1 · 150 ₽")
        assertEquals(listOf(listOf("Платеж отправлен · 150 ₽"),listOf("⬅️ Назад","Меню")),rows(2))
        assertTrue(latest(2).text!!.contains("Отправь Игрок 1 150 ₽"))
        click(2,"⬅️ Назад");click(2,"Игрок 1 · 150 ₽");val saved=click(2,"Платеж отправлен · 150 ₽");bot.handle(saved)
        assertEquals("Нет доступных платежей",latest(2).text)
        assertEquals(mapOf(1L to 150L,2L to -150L),bot.service.balances(Access(-1,2)))
        click(2,"⬅️ Назад");click(2,"История платежей")
        assertTrue(latest(2).text!!.contains("В процессе"));assertTrue(fake.richMessages.getValue(2L to latest(2).id).contains("<table>"))
        open(1);click(1,"Принять платеж");click(1,"Игрок 2 · 150 ₽ · 13.09.2026")
        assertEquals("Нет доступных платежей",latest(1).text)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
        click(1,"⬅️ Назад");click(1,"История платежей");assertTrue(latest(1).text!!.contains("Выполнен"))
        click(1,"⬅️ Назад");click(1,"Назад");assertEquals("Что хочешь сделать?",latest(1).text)
        assertTrue(fake.messages.values.none { it.chat.id<0 })
    }
    @Test fun `empty lists and another group never show payments from the first group`() {
        setup();seedBalance();run(SettlementCommand.SendPayment("p",1,150),Access(-1,2))
        open(1,"Группа 2");click(1,"Принять платеж");assertEquals("Нет доступных платежей",latest(1).text)
        click(1,"⬅️ Назад");click(1,"Отправить платеж");assertEquals("Нет доступных платежей",latest(1).text)
        open(1);click(1,"Принять платеж");assertTrue(rows(1).flatten().any { it.contains("150 ₽") })
    }
    @Test fun `available sends use three rows and history and debtors five rows per page`() {
        setup()
        for(u in 3L..11L) run(SettlementCommand.RecordTransfer("seed$u",u,2,30,"2026-09-13"),Access(-1,u))
        open(2);click(2,"Отправить платеж")
        assertEquals(3,rows(2).flatten().count { it.startsWith("Игрок ") })
        click(2,"Дальше ›");val row=rows(2).first().single();click(2,row);click(2,"⬅️ Назад")
        assertTrue(rows(2).flatten().contains("2 / 3"));assertEquals(row,rows(2).first().single())
        click(2,"⬅️ Назад");click(2,"История платежей")
        assertEquals(6,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
        assertTrue(rows(2).flatten().contains("1 / 2"));click(2,"Дальше ›")
        assertEquals(5,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
        for(u in 3L..11L) run(SettlementCommand.RecordTransfer("reverse$u",2,u,60,"2026-09-13"),Access(-1,2))
        open(2);click(2,"Должники")
        assertEquals(6,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
        assertTrue(rows(2).flatten().contains("1 / 2"));click(2,"Дальше ›")
        assertEquals(5,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
    }
    @Test fun `pending receipts use three rows and clicking a receipt removes only that row`() {
        setup()
        for(i in 1..7) {
            run(SettlementCommand.RecordTransfer("incoming$i",1,2,i*10L,"2026-09-13"))
            run(SettlementCommand.ChangeTransfer("incoming$i",1,TransferChange.REVIEW))
        }
        open(2);click(2,"Принять платеж")
        assertEquals(3,rows(2).flatten().count { it.startsWith("Игрок ") });val row=rows(2).first().single()
        click(2,row);assertFalse(rows(2).flatten().contains(row))
        assertEquals(6,bot.service.financePayments(Access(-1,2),incomingOnly=true).total)
        assertEquals(1,bot.service.financePayments(Access(-1,2)).items.count { it.status==PaymentStatus.ACTIVE })
    }
    @Test fun `retired callbacks and persisted transfer forms cannot bypass receipt confirmation`() {
        setup();seedBalance()
        val command=SettlementCommand.RecordTransfer("legacy",2,1,150,"2026-09-13")
        val pending=TgUpdate(seq++,TgMessage(seq,TgChat(2,"private"),TgUser(2),"150"))
        bot.state.plan(pending.id,EventPlan(2,2,ScreenAction("transfer",-1,"legacy"),command))
        bot.handle(pending)
        assertEquals(0,bot.service.financePayments(Access(-1,2)).total)
        assertTrue(rows(2).flatten().contains("Отправить платеж"))
        val old=bot.state.button(ScreenAction("save_transfer",-1),2,"personal:2:2")
        bot.handle(TgUpdate(seq++,callback=TgCallback("retired",TgUser(2),latest(2),"n:$old")))
        assertEquals(0,bot.service.financePayments(Access(-1,2)).total)
        bot.state.session(2,2,-1,InputForm("transfer_amount",-1,user=1))
        message(2,"150")
        assertNull(bot.state.form(2,2));assertEquals(0,bot.service.financePayments(Access(-1,2)).total)
    }
}
