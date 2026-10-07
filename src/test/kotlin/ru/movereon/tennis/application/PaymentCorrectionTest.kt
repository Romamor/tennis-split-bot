package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class PaymentCorrectionTest {
    @TempDir lateinit var dir:Path
    private val statements=mutableListOf<String>()
    private lateinit var service:SettlementService
    private var sequence=0
    private val admin=Access(-1,3,true)
    private val sender=Access(-1,1)
    private val receiver=Access(-1,2)
    private fun setup() {
        service=SettlementService(Database(dir.resolve("corrections.sqlite"),statements::add))
        for(g in listOf(-1L,-2L)) {
            service.register(SettlementGroup(g,"Группа","Europe/Moscow"))
            for(u in 1L..20L) { service.remember(Account(u,"Игрок $u"));service.rememberMembership(g,u,true) }
        }
    }
    private fun execute(c:SettlementCommand,a:Access=sender)=service.execute(a,"request-${sequence++}",c)
    @Test fun `ordinary sender cancels only pending and admins can change any status without losing audit`() {
        setup();execute(SettlementCommand.SendOtherPayment("p",2,100))
        val original=service.transfer(sender,"p");val count=service.history(sender).total
        for(a in listOf(receiver,Access(-1,4),Access(-2,1,true))) {
            assertFailsWith<AccountingException> { execute(SettlementCommand.CancelPayment("p",1),a) }
            assertFailsWith<AccountingException> { execute(SettlementCommand.EditPaymentAmount("p",1,200),a) }
        }
        assertEquals(original,service.transfer(sender,"p"));assertEquals(count,service.history(sender).total)
        execute(SettlementCommand.EditPaymentAmount("p",1,150),admin)
        assertEquals(PaymentStatus.REVIEW,service.transfer(sender,"p").status);assertTrue(service.balances(sender).isEmpty())
        execute(SettlementCommand.ReceivePayment("p"),receiver)
        assertFailsWith<AccountingException> { execute(SettlementCommand.CancelPayment("p",3)) }
        execute(SettlementCommand.EditPaymentAmount("p",3,250),admin)
        assertEquals(mapOf(1L to 250L,2L to -250L),service.balances(sender))
        val command=SettlementCommand.CancelPayment("p",4)
        val receipt=service.execute(admin,"cancel",command);assertEquals(receipt,service.execute(admin,"cancel",command))
        assertTrue(service.balances(sender).values.all { it==0L })
        execute(SettlementCommand.EditPaymentAmount("p",5,300),admin)
        assertEquals(PaymentStatus.CANCELLED,service.transfer(sender,"p").status)
        assertTrue(service.balances(sender).values.all { it==0L })
        assertEquals(0,service.pendingPaymentCount(receiver));assertTrue("p" in service.editedPayments(sender,listOf("p")))
        assertTrue(service.paymentDetails(sender,"p").edits.any { it.kind=="AdminCancelPayment" })
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }
    @Test fun `stale version revoked membership invalid sums and reservation overflow roll back everything`() {
        setup();execute(SettlementCommand.SendOtherPayment("p",2,100))
        execute(SettlementCommand.EditPaymentAmount("p",1,150),admin)
        assertFailsWith<AccountingException> { execute(SettlementCommand.CancelPayment("p",1)) }
        val old=service.transfer(sender,"p");val history=service.history(sender).total
        for(amount in listOf(0L,-1L)) assertFailsWith<IllegalArgumentException> { execute(SettlementCommand.EditPaymentAmount("p",2,amount),admin) }
        assertFailsWith<AccountingException> { execute(SettlementCommand.EditPaymentAmount("p",1,200),admin) }
        service.rememberMembership(-1,3,false)
        assertFailsWith<AccountingException> { execute(SettlementCommand.EditPaymentAmount("p",2,200),admin) }
        assertFailsWith<AccountingException> { execute(SettlementCommand.CancelPayment("p",2),admin) }
        service.rememberMembership(-1,3,true)
        assertEquals(old,service.transfer(sender,"p"));assertEquals(history,service.history(sender).total)
        execute(SettlementCommand.SendOtherPayment("second",2,50))
        val before=service.history(sender).total
        assertFailsWith<AccountingException> { execute(SettlementCommand.EditPaymentAmount("p",2,Long.MAX_VALUE),admin) }
        assertEquals(150,service.transfer(sender,"p").amount);assertEquals(before,service.history(sender).total)
        assertTrue(service.balances(sender).isEmpty())
    }
    @Test fun `receipt cancellation race posts either receipt or sender cancellation without corruption`() {
        setup();execute(SettlementCommand.SendOtherPayment("p",2,100))
        val pool=Executors.newFixedThreadPool(2)
        try {
            val results=listOf(pool.submit<Boolean> { runCatching { service.execute(sender,"cancel",SettlementCommand.CancelPayment("p",1)) }.isSuccess },
                pool.submit<Boolean> { runCatching { service.execute(receiver,"receive",SettlementCommand.ReceivePayment("p")) }.isSuccess })
            assertEquals(1,results.count { it.get() })
            val t=service.transfer(sender,"p")
            if(t.status==PaymentStatus.ACTIVE) assertEquals(mapOf(1L to 100L,2L to -100L),service.balances(sender))
            else { assertEquals(PaymentStatus.CANCELLED,t.status);assertTrue(service.balances(sender).values.all { it==0L }) }
            assertTrue(service.database.verify().contains("целостность в порядке"))
        } finally { pool.shutdownNow() }
    }
    @Test fun `history uses one page select instead of one query per transfer`() {
        setup();repeat(21) { execute(SettlementCommand.SendOtherPayment("p$it",2,it+1L)) }
        statements.clear()
        val first=service.financePayments(Access(-1,4),allGroup=true)
        assertEquals(10,first.items.size);assertEquals(21,first.total)
        assertEquals(1,statements.count { it.startsWith("SELECT * FROM transfers") })
        assertTrue(statements.size<=4)
        assertEquals(15,service.financeBalances(receiver).items.size)
        assertEquals(5,service.financeBalances(receiver,1).items.size)
        assertEquals(19,service.financeBalances(receiver,exclude=2).total)
    }
}
