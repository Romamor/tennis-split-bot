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
        val button=source.keyboard!!.rows.flatten().single { it.text==text || it.text.endsWith(" $text") }
        return TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(u,firstName="Игрок $u"),source,button.callbackData)).also(bot::handle)
    }
    private fun open(u:Long,group:String="Группа 1") { message(u,"/start");click(u,"💰 Мои финансы");click(u,group) }
    private fun receive(u:Long) { click(u,rows(u).flatten().single { it.startsWith("Принять платеж(") }) }
    private fun accept(u:Long,label:String) {
        click(u,label);click(u,"Да, получил");click(u,"К моим финансам");receive(u)
    }
    private fun seedBalance(id:String="t",paid:Long=300) {
        run(SettlementCommand.CreateTraining(id,"Теннис","2026-09-13","18:30"))
        run(SettlementCommand.AddPlayers(id,1,listOf(1,2)))
        for(u in 1L..2L) run(SettlementCommand.ChangeAttendance(id,u,AttendanceChange.ADJUST_MINUTES,60))
        run(SettlementCommand.ChangeAttendance(id,1,AttendanceChange.SET_PAID,paid))
        run(SettlementCommand.FinishTraining(id,bot.service.training(Access(-1,1),id).version))
    }
    @Test fun `send and receive screens follow the diagram and return to the right menus`() {
        setup();seedBalance();open(2)
        assertEquals("Мои финансы:\nБаланс: −150 ₽ — тебе осталось внести",latest(2).text)
        assertEquals(listOf(listOf("Отправить платеж","Принять платеж(0)"),listOf("Другой платёж","История платежей"),listOf("💰 Баланс группы"),listOf("⬅️ Назад")),rows(2))
        click(2,"Отправить платеж");click(2,"Игрок 1 · 150 ₽")
        assertEquals(listOf(listOf("💸 Платеж отправлен · 150 ₽"),listOf("⬅️ Назад","Меню")),rows(2))
        assertTrue(latest(2).text!!.contains("Отправь Игрок 1 150 ₽"))
        click(2,"⬅️ Назад");click(2,"Игрок 1 · 150 ₽");val saved=click(2,"Платеж отправлен · 150 ₽");bot.handle(saved)
        assertEquals("Нет доступных платежей",latest(2).text)
        assertEquals(mapOf(1L to 150L,2L to -150L),bot.service.balances(Access(-1,2)))
        open(2);assertTrue(latest(2).text!!.contains("Отправлено, ждёт подтверждения: 1 · 150 ₽"))
        open(1);assertTrue(latest(1).text!!.contains("Тебе подтвердить получение: 1 · 150 ₽"))
        click(2,"История платежей")
        assertTrue(latest(2).text!!.contains("В процессе"));assertTrue(fake.richMessages.getValue(2L to latest(2).id).contains("<table bordered striped compact>"))
        open(1);receive(1);accept(1,"Игрок 2 · 150 ₽ · 13.09.2026")
        assertEquals("Нет доступных платежей",latest(1).text)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
        click(1,"⬅️ Назад");click(1,"История платежей");assertTrue(latest(1).text!!.contains("Выполнен"))
        click(1,"⬅️ Назад");click(1,"Назад");assertEquals("Что хочешь сделать?",latest(1).text)
        assertTrue(fake.messages.values.none { it.chat.id<0 })
    }
    @Test fun `pending payment stays out of sends while a later training creates a separate suggestion`() {
        setup();seedBalance();open(2);click(2,"Отправить платеж")
        click(2,"Игрок 1 · 150 ₽");click(2,"Платеж отправлен · 150 ₽")
        assertEquals("Нет доступных платежей",latest(2).text)
        seedBalance("next",500)
        click(2,"⬅️ Назад");click(2,"Отправить платеж")
        assertEquals(listOf("💸 Игрок 1 · 250 ₽"),rows(2).flatten().filter { it.contains("Игрок ") })
        click(2,"Игрок 1 · 250 ₽");click(2,"Платеж отправлен · 250 ₽")
        assertEquals("Нет доступных платежей",latest(2).text)
        open(1);receive(1)
        assertTrue(rows(1).flatten().containsAll(listOf("📥 Игрок 2 · 150 ₽ · 13.09.2026","📥 Игрок 2 · 250 ₽ · 13.09.2026")))
        accept(1,"Игрок 2 · 250 ₽ · 13.09.2026")
        assertTrue(rows(1).flatten().contains("📥 Игрок 2 · 150 ₽ · 13.09.2026"))
        accept(1,"Игрок 2 · 150 ₽ · 13.09.2026")
        assertEquals("Нет доступных платежей",latest(1).text)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
    }
    @Test fun `empty lists and another group never show payments from the first group`() {
        setup();seedBalance();run(SettlementCommand.SendPayment("p",1,150),Access(-1,2))
        open(1,"Группа 2");receive(1);assertEquals("Нет доступных платежей",latest(1).text)
        click(1,"⬅️ Назад");click(1,"Отправить платеж");assertEquals("Нет доступных платежей",latest(1).text)
        open(1);receive(1);assertTrue(rows(1).flatten().any { it.contains("150 ₽") })
    }
    @Test fun `available sends use three rows history five and group balances ten rows per page`() {
        setup()
        for(u in 3L..11L) run(SettlementCommand.RecordTransfer("seed$u",u,2,30,"2026-09-13"),Access(-1,u))
        open(2);click(2,"Отправить платеж")
        assertEquals(3,rows(2).flatten().count { it.contains("Игрок ") })
        click(2,"Дальше ›");val row=rows(2).first().single();click(2,row);click(2,"⬅️ Назад")
        assertTrue(rows(2).flatten().contains("2 / 3"));assertEquals(row,rows(2).first().single())
        click(2,"⬅️ Назад");click(2,"История платежей")
        assertEquals(6,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
        assertTrue(rows(2).flatten().contains("1 / 2"));click(2,"Дальше ›")
        assertEquals(5,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
        for(u in 3L..11L) run(SettlementCommand.RecordTransfer("reverse$u",2,u,60,"2026-09-13"),Access(-1,2))
        run(SettlementCommand.RecordTransfer("extra",12,2,30,"2026-09-13"),Access(-1,12))
        open(2);click(2,"Баланс группы")
        assertTrue(fake.richMessages.getValue(2L to latest(2).id).contains("<table bordered striped compact>"))
        assertEquals(11,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
        assertTrue(rows(2).flatten().contains("1 / 2"));click(2,"Дальше ›")
        assertEquals(2,Regex("<tr>").findAll(fake.richMessages.getValue(2L to latest(2).id)).count())
    }
    @Test fun `pending receipts use three rows and clicking a receipt removes only that row`() {
        setup()
        for(i in 1..7) {
            run(SettlementCommand.RecordTransfer("incoming$i",1,2,i*10L,"2026-09-13"))
            run(SettlementCommand.ChangeTransfer("incoming$i",1,TransferChange.REVIEW))
        }
        open(2);receive(2)
        assertEquals(3,rows(2).flatten().count { it.contains("Игрок ") });val row=rows(2).first().single()
        accept(2,row);assertFalse(rows(2).flatten().contains(row))
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
    @Test fun `other payment input waits for receipt detects duplicates and moves the menu below text`() {
        setup();open(2);val menu=latest(2);click(2,"Другой платёж");click(2,"Игрок 1")
        assertTrue(latest(2).text!!.contains("Напиши сумму в рублях"))
        val before=latest(2).id
        message(2,"125");assertTrue(latest(2).id>before)
        click(2,"Другой платёж",menu);assertEquals(125,bot.state.form(2,2)!!.amount)
        assertEquals(listOf(listOf("✅ Платёж отправлен"),listOf("Назад","Отмена")),rows(2))
        click(2,"Назад");assertEquals(125,bot.state.form(2,2)!!.amount)
        message(2,"125");val submit=click(2,"Платёж отправлен");bot.handle(submit)
        assertNull(bot.state.form(2,2));assertTrue(latest(2).text!!.contains("Ожидает подтверждения"))
        assertTrue(bot.service.balances(Access(-1,2)).isEmpty())
        click(2,"К моим финансам");click(2,"Другой платёж");click(2,"Игрок 1");message(2,"125")
        click(2,"Платёж отправлен");assertTrue(latest(2).text!!.contains("Это ещё один платёж"))
        click(2,"Посмотреть предыдущий");assertTrue(latest(2).text!!.contains("125 ₽"));click(2,"Назад")
        assertTrue(latest(2).text!!.contains("Это ещё один платёж"));click(2,"Отмена")
        assertEquals(1,bot.service.pendingPaymentCount(Access(-1,1)))
        open(1);assertTrue(rows(1).flatten().contains("Принять платеж(1)"));receive(1)
        accept(1,"Игрок 2 · 125 ₽ · 13.09.2026")
        click(1,"⬅️ Назад");assertTrue(rows(1).flatten().contains("Принять платеж(0)"))
        assertEquals(mapOf(1L to -125L,2L to 125L),bot.service.balances(Access(-1,1)))
    }
    @Test fun `admin can record between other accounts with attribution and revoked rights stop saved input`() {
        setup();fake.members[-1L to 1L]=TgMember("administrator")
        open(1);click(1,"Записать платёж за участников");click(1,"Игрок 2");click(1,"Игрок 3");message(1,"75")
        val pending=latest(1)
        fake.members[-1L to 1L]=TgMember("member");click(1,"Записать платёж",pending)
        assertTrue(alerts.last().contains("администратору"));assertTrue(bot.service.balances(Access(-1,1)).isEmpty())
        fake.members[-1L to 1L]=TgMember("administrator");val saved=click(1,"Записать платёж",pending);bot.handle(saved)
        assertTrue(latest(1).text!!.contains("Записал администратор: Игрок 1"))
        assertEquals(mapOf(2L to 75L,3L to -75L),bot.service.balances(Access(-1,1)))
        assertEquals(0,bot.service.pendingPaymentCount(Access(-1,3)))
        open(3);click(3,"История платежей");click(3,rows(3).first().single())
        assertTrue(latest(3).text!!.contains("Записал администратор: Игрок 1"))
        assertEquals(listOf(listOf("⬅️ Назад")),rows(3))
        open(1,"Группа 2");assertFalse(rows(1).flatten().contains("Записать платёж за участников"))
    }
    @Test fun `incoming counter includes every page while forms reject stale and cross group buttons`() {
        setup()
        for(i in 1L..7L) run(SettlementCommand.SendOtherPayment("p$i",2,i),Access(-1,1))
        open(2);assertTrue(rows(2).flatten().contains("Принять платеж(7)"))
        open(2,"Группа 2");assertTrue(rows(2).flatten().contains("Принять платеж(0)"))
        open(1);assertTrue(rows(1).flatten().contains("Принять платеж(0)"))
        click(1,"Другой платёж");click(1,"Игрок 2");message(1,"11")
        val stale=latest(1);click(1,"Назад");message(1,"12");click(1,"Платёж отправлен",stale)
        assertTrue(alerts.last().contains("Ввод изменился"));assertEquals(7,bot.service.pendingPaymentCount(Access(-1,2)))
        click(1,"Отмена");assertNull(bot.state.form(1,1))
    }

}
