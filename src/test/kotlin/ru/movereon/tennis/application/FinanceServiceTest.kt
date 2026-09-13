package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class FinanceServiceTest {
    @TempDir lateinit var dir:Path
    private lateinit var s:SettlementService
    private var seq=0
    private val payer=Access(-1,2)
    private val recipient=Access(-1,1)
    private fun run(c:SettlementCommand,a:Access=recipient)=s.execute(a,"seed${seq++}",c)
    private fun setup() {
        s=SettlementService(Database(dir.resolve("finance.sqlite")))
        for(g in -2L..-1L) {
            s.register(SettlementGroup(g,"Группа","Europe/Moscow"))
            for(u in 1L..12L) { s.remember(Account(u,"Игрок $u"));s.rememberMembership(g,u,true) }
        }
        run(SettlementCommand.CreateTraining("t","Теннис","2026-09-13","18:30"))
        run(SettlementCommand.AddPlayers("t",1,listOf(1,2)))
        for(u in 1L..2L) run(SettlementCommand.ChangeAttendance("t",u,AttendanceChange.ADJUST_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300))
        run(SettlementCommand.FinishTraining("t",s.training(recipient,"t").version))
    }
    @Test fun `sending reserves amount while only recipient confirmation changes posted balances`() {
        setup();val before=s.balances(payer)
        val sent=s.execute(payer,"send",SettlementCommand.SendPayment("p",1,150))
        assertEquals(sent,s.execute(payer,"send",SettlementCommand.SendPayment("p",1,150)))
        assertEquals(before,s.balances(payer));assertEquals(0,s.paymentSuggestions(payer).total)
        assertEquals(1,s.financePayments(recipient,incomingOnly=true).total)
        assertFailsWith<AccountingException> { run(SettlementCommand.ReceivePayment("p"),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.ReceivePayment("p"),Access(-1,3,true)) }
        val receipt=s.execute(recipient,"receive",SettlementCommand.ReceivePayment("p"))
        assertEquals(receipt,s.execute(recipient,"receive",SettlementCommand.ReceivePayment("p")))
        assertEquals(0,s.execute(recipient,"another-receive",SettlementCommand.ReceivePayment("p")).id)
        assertTrue(s.balances(payer).values.all { it==0L })
        assertEquals(0,s.financePayments(recipient,incomingOnly=true).total)
        assertEquals(PaymentStatus.ACTIVE,s.transfer(recipient,"p").status)
    }
    @Test fun `stale suggestions arbitrary recipients and cross group identifiers cannot send money`() {
        setup();val before=s.balances(payer)
        assertFailsWith<AccountingException> { run(SettlementCommand.SendPayment("wrong",1,149),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.SendPayment("wrong-to",3,150),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.SendPayment("elsewhere",1,150),Access(-2,2)) }
        run(SettlementCommand.SendPayment("p",1,150),payer)
        assertFailsWith<AccountingException> { run(SettlementCommand.SendPayment("again",1,150),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.ReceivePayment("p"),Access(-2,1,true)) }
        assertEquals(before,s.balances(payer));assertEquals(1,s.financePayments(payer).total)
    }
    @Test fun `new payments cannot be accepted or rewritten through retired commands`() {
        setup();run(SettlementCommand.SendPayment("p",1,150),payer)
        val t=s.transfer(payer,"p");val before=s.balances(payer)
        assertFailsWith<AccountingException> { run(SettlementCommand.ChangeTransfer("p",t.version,TransferChange.CONFIRM),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.EditTransferAmount("p",t.version,300),recipient) }
        assertEquals(before,s.balances(payer));assertEquals(t,s.transfer(payer,"p"))
    }
    @Test fun `concurrent different send requests reserve a suggestion once`() {
        setup();val pool=Executors.newFixedThreadPool(2)
        try {
            val calls=(1..2).map { i -> pool.submit<Boolean> { runCatching { s.execute(payer,"parallel$i",SettlementCommand.SendPayment("p$i",1,150)) }.isSuccess } }
            assertEquals(1,calls.count { it.get() })
            assertEquals(1,s.financePayments(payer).total)
            assertEquals(mapOf(1L to 150L,2L to -150L),s.balances(payer))
        } finally { pool.shutdownNow() }
    }
    @Test fun `pending payment survives training reopen and restart without posting until receipt`() {
        setup();run(SettlementCommand.SendPayment("p",1,150),payer)
        run(SettlementCommand.ReopenTraining("t",s.training(recipient,"t").version))
        assertTrue(s.balances(payer).values.all { it==0L })
        s=SettlementService(Database(dir.resolve("finance.sqlite")))
        run(SettlementCommand.ReceivePayment("p"))
        assertEquals(mapOf(1L to -150L,2L to 150L),s.balances(payer))
        run(SettlementCommand.FinishTraining("t",s.training(recipient,"t").version))
        assertTrue(s.balances(payer).values.all { it==0L })
        assertTrue(s.database.verify().contains("целостность в порядке"))
    }
    @Test fun `a newly accounted training offers only the new amount while earlier payment awaits receipt`() {
        setup()
        run(SettlementCommand.SendPayment("earlier",1,150),payer)
        val earlier=s.transfer(recipient,"earlier")
        run(SettlementCommand.CreateTraining("next","Следующая тренировка","2026-09-14","18:30"))
        run(SettlementCommand.AddPlayers("next",1,listOf(1,2)))
        for(u in 1L..2L) run(SettlementCommand.ChangeAttendance("next",u,AttendanceChange.ADJUST_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("next",1,AttendanceChange.SET_PAID,500))
        run(SettlementCommand.FinishTraining("next",s.training(recipient,"next").version))
        assertEquals(mapOf(1L to 400L,2L to -400L),s.balances(payer))
        val proposal=s.paymentSuggestions(payer).items.single()
        assertEquals("1",proposal.to.value);assertEquals(250L,proposal.amount)
        assertEquals(earlier,s.transfer(recipient,"earlier"))
        assertFailsWith<AccountingException> { run(SettlementCommand.SendPayment("stale",1,150),payer) }
        run(SettlementCommand.SendPayment("later",1,250),payer)
        assertEquals(0,s.paymentSuggestions(payer).total)
        assertEquals(setOf(150L,250L),s.financePayments(recipient,incomingOnly=true).items.map { it.amount }.toSet())
        assertEquals(2,s.financePayments(recipient,incomingOnly=true).total)
        // Receipt order must not merge the records or reserve the older amount twice.
        run(SettlementCommand.ReceivePayment("later"))
        assertEquals(mapOf(1L to 150L,2L to -150L),s.balances(payer))
        assertEquals(0,s.paymentSuggestions(payer).total)
        assertEquals(earlier,s.transfer(recipient,"earlier"))
        run(SettlementCommand.ReceivePayment("earlier"))
        assertTrue(s.balances(payer).values.all { it==0L })
        assertEquals(2,s.financePayments(recipient).total)
        assertTrue(s.financePayments(recipient).items.all { it.status==PaymentStatus.ACTIVE })
    }
    @Test fun `sending and receiving require current membership even for administrators`() {
        setup();s.rememberMembership(-1,2,false)
        assertFailsWith<AccountingException> { run(SettlementCommand.SendPayment("p",1,150),Access(-1,2,true)) }
        s.rememberMembership(-1,2,true);run(SettlementCommand.SendPayment("p",1,150),payer)
        s.rememberMembership(-1,1,false)
        assertFailsWith<AccountingException> { run(SettlementCommand.ReceivePayment("p"),Access(-1,1,true)) }
        assertEquals(PaymentStatus.REVIEW,s.transfer(payer,"p").status)
    }
    @Test fun `arbitrary transfers support advances with duplicate guard and recipient only posting`() {
        setup();val before=s.balances(payer)
        val command=SettlementCommand.SendOtherPayment("advance",3,125)
        val receipt=s.execute(payer,"send-other",command)
        assertEquals(receipt,s.execute(payer,"send-other",command));assertEquals(before,s.balances(payer))
        assertFailsWith<DuplicateTransfer> { run(command.copy(id="duplicate"),payer) }
        assertEquals(1,s.pendingPaymentCount(Access(-1,3)))
        run(command.copy(id="second",allowSimilar=true),payer)
        assertEquals(2,s.pendingPaymentCount(Access(-1,3)))
        assertFailsWith<AccountingException> { run(SettlementCommand.ReceivePayment("advance"),Access(-1,1,true)) }
        assertFailsWith<AccountingException> { run(SettlementCommand.EditTransferAmount("advance",1,200),payer) }
        run(SettlementCommand.ReceivePayment("advance"),Access(-1,3))
        assertEquals(mapOf(1L to 150L,2L to -25L,3L to -125L),s.balances(payer))
        assertEquals(1,s.pendingPaymentCount(Access(-1,3)))
        assertFailsWith<IllegalArgumentException> { run(SettlementCommand.SendOtherPayment("self",2,1),payer) }
        assertFailsWith<IllegalArgumentException> { run(SettlementCommand.SendOtherPayment("zero",3,0),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.SendOtherPayment("foreign",99,1),payer) }
    }
    @Test fun `administrative payments post immediately and cannot be disputed through legacy commands`() {
        setup();val command=SettlementCommand.RecordAdminPayment("manual",2,3,75)
        assertFailsWith<AccountingException> { run(command,recipient) }
        val receipt=s.execute(Access(-1,1,true),"admin-record",command)
        assertEquals(receipt,s.execute(Access(-1,1,true),"admin-record",command))
        assertEquals(mapOf(1L to 150L,2L to -75L,3L to -75L),s.balances(payer))
        assertEquals(0,s.pendingPaymentCount(Access(-1,3)));assertTrue(s.administrativePayment(payer,"manual"))
        assertEquals(1,s.transfer(payer,"manual").createdBy)
        assertFailsWith<AccountingException> { run(SettlementCommand.ChangeTransfer("manual",1,TransferChange.REVIEW),payer) }
        assertFailsWith<AccountingException> { run(SettlementCommand.EditTransferAmount("manual",1,100),Access(-1,1,true)) }
        run(SettlementCommand.SetAdministrator(4,true),Access(-1,1,true))
        run(command.copy(id="delegated"),Access(-1,4))
        assertFailsWith<AccountingException> { run(command.copy(id="other-group"),Access(-2,4)) }
        s.rememberMembership(-1,4,false)
        assertFailsWith<AccountingException> { run(command.copy(id="left"),Access(-1,4)) }
    }
    @Test fun `group balances sort all signed amounts before zero rows across pages`() {
        setup()
        run(SettlementCommand.CreateTraining("open","Теннис","2026-09-14","18:30"))
        run(SettlementCommand.AddPlayers("open",1,(3L..9L).toList()))
        run(SettlementCommand.RecordAdminPayment("a",3,4,200),Access(-1,1,true))
        run(SettlementCommand.RecordAdminPayment("b",5,6,50),Access(-1,1,true))
        val all=s.financeBalances(recipient).items+s.financeBalances(recipient,1).items
        assertEquals(listOf(200L,150L,50L,-50L,-150L,-200L,0L,0L,0L),all.map { it.balance })
        assertEquals(9,s.financeBalances(recipient).total);assertEquals(5,s.financeBalances(recipient).items.size)
        assertTrue(s.financeBalances(Access(-2,1)).items.isEmpty())
    }

    @Test fun `arbitrary pending overflow rolls back payment and history`() {
        setup();run(SettlementCommand.SendOtherPayment("large",4,Long.MAX_VALUE),Access(-1,3))
        val before=s.history(recipient).total
        assertFailsWith<AccountingException> { run(SettlementCommand.SendOtherPayment("overflow",4,1),Access(-1,3)) }
        assertEquals(before,s.history(recipient).total);assertEquals(1,s.pendingPaymentCount(Access(-1,4)))
        assertFailsWith<AccountingException> { s.transfer(recipient,"overflow") }
        assertFailsWith<AccountingException> { run(SettlementCommand.RecordAdminPayment("admin-overflow",3,4,1),Access(-1,1,true)) }
        assertEquals(before,s.history(recipient).total)
    }

}
