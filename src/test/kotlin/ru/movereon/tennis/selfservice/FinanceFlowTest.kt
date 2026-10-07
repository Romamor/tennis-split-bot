package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

/** Whole update -> permission -> draft -> ledger -> rendered-message regression tests. */
class FinanceFlowTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private val queries=mutableListOf<String>()
    private var editCalls=0
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
        override fun editRich(chatId:Long,messageId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?) {
            editCalls++;fake.editRich(chatId,messageId,text,html,keyboard,photoId)
        }
    }
    private lateinit var bot:SelfServiceBot
    private var seq=1L
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite"),queries::add),fake.bot,Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"),ZoneOffset.UTC))
        for(g in -2L..-1L) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..20L) { bot.service.remember(Account(u,"Игрок $u"));bot.service.rememberMembership(g,u,true);fake.members[g to u]=TgMember("member") }
            fake.members[g to fake.bot.id]=TgMember("administrator",user=fake.bot)
        }
    }
    private fun run(c:SettlementCommand,a:Access=Access(-1,1))=bot.service.execute(a,"seed${seq++}",c)
    private fun latest(u:Long)=fake.messages.values.last { it.chat.id==u }
    private fun rows(u:Long)=latest(u).keyboard!!.rows.map { row->row.map { it.text } }
    private fun html(u:Long)=fake.richMessages.getValue(u to latest(u).id)
    private fun message(u:Long,text:String) { bot.handle(TgUpdate(seq++,TgMessage(seq,TgChat(u,"private"),TgUser(u,firstName="Игрок $u"),text))) }
    private fun click(u:Long,text:String,source:TgMessage=latest(u)):TgUpdate {
        val button=source.keyboard!!.rows.flatten().single { it.text==text || it.text.endsWith(" $text") }
        assertNull(button.disabled,"Button $text should be enabled")
        return callback(u,source,requireNotNull(button.callbackData))
    }
    private fun callback(u:Long,source:TgMessage,data:String)=TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(u,firstName="Игрок $u"),source,data)).also(bot::handle)
    private fun inline(u:Long,id:Long,value:Long=0) {
        val token=Regex("<tg-button[^>]*data=\"([^\"]+)\"[^>]*>").findAll(html(u)).map { it.groupValues[1] }.single { data ->
            val a=bot.state.button(data.removePrefix("n:"))!!.action;a.user==id && a.value==value
        }
        callback(u,latest(u),token)
    }
    private fun open(u:Long,group:String="Группа 1") { message(u,"/start");click(u,"Мои финансы");click(u,group) }
    private fun receive(u:Long) { click(u,rows(u).flatten().single { it.contains("Принять перевод(") }) }
    private fun own(u:Long,to:Long,amount:String) {
        click(u,"Перевести");click(u,"Записать свой перевод");inline(u,to);click(u,"Ввести сумму сообщением");message(u,amount)
    }
    private fun seedBalance(id:String="t",paid:Long=300) {
        run(SettlementCommand.CreateTraining(id,"Теннис","2026-10-07","18:30"));run(SettlementCommand.AddPlayers(id,1,listOf(1,2)))
        for(u in 1L..2L) run(SettlementCommand.ChangeAttendance(id,u,AttendanceChange.ADJUST_MINUTES,60))
        run(SettlementCommand.ChangeAttendance(id,1,AttendanceChange.SET_PAID,paid))
        run(SettlementCommand.FinishTraining(id,bot.service.training(Access(-1,1),id).version))
    }
    @Test fun `approved main menu calculated send and receipt confirmation never double post`() {
        setup();seedBalance();open(2)
        assertEquals("Мои финансы:\n−150 ₽ — оплачено за тебя другими участниками группы",latest(2).text)
        assertEquals(listOf(listOf("📤 Перевести","📥 Принять перевод(0)"),listOf("💰 Баланс группы"),listOf("📜 История переводов"),listOf("⬅️ Назад")),rows(2))
        click(2,"Перевести");click(2,"Игрок 1 · 150 ₽")
        assertTrue(html(2).contains("150 ₽"));assertTrue(latest(2).text!!.contains("Игрок 1 (+150 ₽)"))
        assertNotNull(latest(2).keyboard!!.rows.flatten().single { it.text=="🧮 Рекомендуется 150 ₽" }.disabled)
        val saved=click(2,"Перевод отправлен");bot.handle(saved)
        assertNull(bot.state.form(2,2));assertTrue(latest(2).text!!.contains("Ожидает подтверждения"))
        assertEquals(mapOf(1L to 150L,2L to -150L),bot.service.balances(Access(-1,2)))
        open(2);click(2,"Перевести");assertTrue(latest(2).text!!.contains("готовых переводов нет"))
        assertTrue(rows(2).flatten().contains("✍️ Записать свой перевод"))
        open(1);receive(1);click(1,"Игрок 2 · 150 ₽ · 07.10.2026")
        assertTrue(latest(1).text!!.contains("Игрок 2 (−150 ₽)"));assertEquals("primary",latest(1).keyboard!!.rows.first().single().style)
        val accepted=click(1,"Да, получил");bot.handle(accepted)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
        assertEquals(1,bot.service.financePayments(Access(-1,1)).total)
    }
    @Test fun `custom amount updates same message rounding stays disabled until balance or recommendation selected`() {
        setup();seedBalance();open(2);click(2,"Баланс группы");inline(2,1)
        val id=latest(2).id;val heights=rows(2).map { it.size }
        assertEquals(0,bot.state.form(2,2)!!.amount)
        click(2,"➕ 100 ₽");assertEquals(100,bot.state.form(2,2)!!.amount)
        assertNotNull(latest(2).keyboard!!.rows.flatten().single { it.text=="🔢 Округлить" }.disabled)
        assertEquals(heights,rows(2).map { it.size })
        click(2,"Ввести сумму сообщением");message(2,"267")
        assertEquals(id,latest(2).id);assertEquals(267,bot.state.form(2,2)!!.amount)
        assertTrue(latest(2).text!!.contains("267 ₽"));assertEquals(0,bot.service.financePayments(Access(-1,2)).total)
        click(2,"Рекомендуется 150 ₽");assertTrue(latest(2).text!!.contains("150 ₽"));assertEquals(id,latest(2).id)
        click(2,"Округлить");assertEquals(200,bot.state.form(2,2)!!.amount)
        click(2,"➖ 50 ₽");assertEquals(150,bot.state.form(2,2)!!.amount)
        click(2,"Ввести сумму сообщением");message(2,"bad")
        assertEquals(150,bot.state.form(2,2)!!.amount);assertTrue(bot.state.form(2,2)!!.waitingForAmount)
        message(2,"99");assertEquals(99,bot.state.form(2,2)!!.amount)
        // Selecting another participant's signed balance uses its absolute amount, in the same form.
        inline(2,1);assertEquals(150,bot.state.form(2,2)!!.amount)
        assertEquals(listOf(1L to 150L,2L to -150L).toMap(),bot.service.balances(Access(-1,2)))
    }
    @Test fun `sender cancellation releases reservations and historical details remain readable to everyone`() {
        setup();seedBalance();open(2);own(2,1,"150");click(2,"Перевод отправлен")
        val transfer=bot.service.financePayments(Access(-1,2)).items.single()
        click(2,"Отменить перевод");val cancel=click(2,"Да, отменить");bot.handle(cancel)
        assertEquals(PaymentStatus.CANCELLED,bot.service.transfer(Access(-1,2),transfer.id).status)
        assertEquals(0,bot.service.pendingPaymentCount(Access(-1,1)));assertEquals(1,bot.service.paymentSuggestions(Access(-1,2)).total)
        open(3);click(3,"История переводов");click(3,"Все")
        val label=rows(3).flatten().single { it.contains("Игрок 2 → Игрок 1") };click(3,label)
        assertTrue(latest(3).text!!.contains("Перевод отменён"));assertEquals(listOf(listOf("⬅️ Назад")),rows(3))
        click(3,"Назад");assertTrue(latest(3).text!!.startsWith("История переводов · Все"))
    }
    @Test fun `all history has ten records and balances have fifteen people with profile and transfer links`() {
        setup();for(i in 1L..22L) run(SettlementCommand.SendOtherPayment("all$i",3,i),Access(-1,2))
        run(SettlementCommand.SendOtherPayment("other",3,999),Access(-2,2))
        open(4);click(4,"История переводов");assertTrue(latest(4).text!!.contains("Список пуст"));click(4,"Все")
        assertEquals(10,rows(4).flatten().count { it.contains("→") });click(4,"Дальше ›")
        val list=latest(4);click(4,rows(4).flatten().first { it.contains("→") });click(4,"Назад")
        assertEquals(list.text,latest(4).text);assertTrue(rows(4).flatten().contains("2 / 3"))
        assertFalse(latest(4).text!!.contains("999 ₽"));click(4,"⬅️ Назад");click(4,"Баланс группы")
        assertEquals(16,Regex("<tr>").findAll(html(4)).count());assertEquals(15,Regex("<a href=").findAll(html(4)).count())
        assertFalse(rows(4).flatten().any { it.contains("Игрок") });click(4,"Дальше ›")
        assertEquals(6,Regex("<tr>").findAll(html(4)).count())
        click(4,"⬅️ Назад");click(4,"Перевести");click(4,"Записать свой перевод")
        assertEquals(16,Regex("<tr>").findAll(html(4)).count());assertFalse(html(4).contains("tg://user?id=4\""))
        click(4,"Назад");assertTrue(latest(4).text!!.startsWith("Перевести"))
        assertEquals(0,bot.service.financePayments(Access(-2,4)).total)
    }
    @Test fun `admin edits confirmed transfer amount and cancels it with ledger reversal and attribution`() {
        setup();fake.members[-1L to 1L]=TgMember("administrator")
        run(SettlementCommand.RecordAdminPayment("p",2,3,75),Access(-1,1,true))
        open(1);click(1,"История переводов");click(1,"Все");click(1,rows(1).flatten().single { it.contains("→") })
        click(1,"Изменить сумму");val id=latest(1).id;message(1,"125")
        assertEquals(id,latest(1).id);assertTrue(latest(1).text!!.contains("Администратор: Игрок 1. Изменил сумму: 75 ₽ → 125 ₽."))
        assertEquals(mapOf(2L to 125L,3L to -125L),bot.service.balances(Access(-1,1)))
        click(1,"Отменить перевод");click(1,"Да, отменить")
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
        assertTrue(latest(1).text!!.contains("Администратор: Игрок 1. Перевод отменён."))
        click(1,"Изменить сумму");message(1,"200")
        assertEquals(PaymentStatus.CANCELLED,bot.service.transfer(Access(-1,1),"p").status)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
    }
    @Test fun `revoked admin cannot edit or cancel a foreign payment using old buttons or pending text form`() {
        setup();fake.members[-1L to 1L]=TgMember("administrator")
        run(SettlementCommand.SendOtherPayment("p",3,75),Access(-1,2));open(1);click(1,"История переводов");click(1,"Все")
        click(1,rows(1).flatten().single { it.contains("→") });val detail=latest(1)
        click(1,"Изменить сумму");fake.members[-1L to 1L]=TgMember("member");message(1,"125")
        assertEquals(75,bot.service.transfer(Access(-1,2),"p").amount)
        click(1,"Изменить сумму",detail);assertTrue(alerts.last().contains("администратор"))
        val n=alerts.size;click(1,"Отменить перевод",detail);assertEquals(n+1,alerts.size)
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
    }
    @Test fun `duplicate confirmation stale forms and foreign group callbacks cannot create unintended transfers`() {
        setup();open(2);own(2,1,"125");val old=latest(2);click(2,"➕ 50 ₽");click(2,"Перевод отправлен",old)
        assertTrue(alerts.last().contains("Ввод изменился"));assertEquals(0,bot.service.financePayments(Access(-1,2)).total)
        click(2,"Перевод отправлен");click(2,"К моим финансам");own(2,1,"175");click(2,"Перевод отправлен")
        assertTrue(latest(2).text!!.contains("Это ещё один перевод"));assertEquals(1,bot.service.pendingPaymentCount(Access(-1,1)))
        click(2,"Да, это ещё один");assertEquals(2,bot.service.pendingPaymentCount(Access(-1,1)))
        val token=bot.state.button(ScreenAction("finance_payment",-2,"p"),2,"personal:2:2")
        callback(2,latest(2),"n:$token");assertTrue(alerts.last().contains("Перевод не найден"))
        assertEquals(0,bot.service.financePayments(Access(-2,2)).total)
    }
    @Test fun `ordinary confirmed sender cannot cancel and pending receipt edits stay pending`() {
        setup();run(SettlementCommand.SendOtherPayment("p",1,100),Access(-1,2));run(SettlementCommand.ReceivePayment("p"))
        open(2);click(2,"История переводов");click(2,rows(2).flatten().single { it.contains("→") })
        assertEquals(listOf(listOf("⬅️ Назад")),rows(2))
        run(SettlementCommand.SendOtherPayment("pending",1,120),Access(-1,2))
        run(SettlementCommand.EditPaymentAmount("pending",1,150),Access(-1,3,true))
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"pending").status)
        assertEquals(mapOf(1L to -100L,2L to 100L),bot.service.balances(Access(-1,2)))
        assertTrue(bot.service.database.verify().contains("целостность в порядке"))
    }
    @Test fun `financial navigation keeps query count bounded and amount changes use one Telegram edit`() {
        setup();seedBalance();open(2);click(2,"Перевести");click(2,"Игрок 1 · 150 ₽")
        bot.service.database.withConnectionReuse {
            val times=mutableListOf<Double>()
            repeat(12) {
                queries.clear();fake.membershipCalls.clear();val sent=fake.sent.size;val edits=editCalls
                val start=System.nanoTime();click(2,"➕ 50 ₽");times+=(System.nanoTime()-start)/1e6
                assertEquals(sent,fake.sent.size);assertEquals(edits+1,editCalls)
                assertTrue(fake.membershipCalls.size<=2,"No per-person network requests")
                assertTrue(queries.size<85,"No SQL work per button or participant: ${queries.size}")
                assertEquals(1,queries.count { it.startsWith("SELECT from_user,to_user,amount") },"Recommendation computed once for the render")
            }
            println("Finance amount changes, 20 members, reused connections: median=${times.sorted()[times.size/2]} ms; max=${times.max()} ms (fake Telegram, network excluded)")
        }
    }
    @Test fun `my training table shows only personal playing time without guests`() {
        setup();seedBalance();run(SettlementCommand.CreateTraining("guest","Гость","2026-10-07","18:30"))
        run(SettlementCommand.AddPlayers("guest",1,listOf(2)));run(SettlementCommand.ChangeAttendance("guest",2,AttendanceChange.SET_MINUTES,90))
        run(SettlementCommand.ChangeAttendance("guest",2,AttendanceChange.ADJUST_GUESTS,1))
        message(2,"/start");click(2,"Мои тренировки")
        assertTrue(html(2).contains("<th>Играл</th>"));assertTrue(html(2).contains("<td>1,5 ч</td>"));assertFalse(html(2).contains("<td>3 ч</td>"))
        run(SettlementCommand.CancelTraining("guest",bot.service.training(Access(-1,1),"guest").version))
        message(2,"/start");click(2,"Мои тренировки")
        assertTrue(html(2).contains("<td></td>"));assertFalse(html(2).contains("<td>1,5 ч</td>"))
        assertEquals(90,bot.service.training(Access(-1,2),"guest").players.single().minutes)

    }
}
