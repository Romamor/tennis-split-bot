package ru.movereon.tennis.application

import java.math.BigInteger
import java.nio.file.Path
import java.time.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import kotlin.test.*

class BalanceHistoryTest {
    @TempDir lateinit var dir:Path
    private lateinit var service:SettlementService
    private val queries=mutableListOf<String>()
    private val owner=Access(-1,1,true)
    private var sequence=0
    private fun setup() {
        service=SettlementService(Database(dir.resolve("history.sqlite"),queries::add),Clock.fixed(Instant.parse("2026-10-16T22:30:00Z"),ZoneOffset.UTC))
        for(g in -2L..-1L) {
            service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..3L) { service.remember(Account(u,"Игрок $u"));service.rememberMembership(g,u,true) }
        }
    }
    private fun run(command:SettlementCommand,a:Access=owner)=service.execute(a,"history-${sequence++}",command)
    private fun version(id:String="t")=service.training(owner,id).version
    private fun training() {
        run(SettlementCommand.CreateTraining("t","Теннис","2026-10-15","18:30"))
        run(SettlementCommand.AddPlayers("t",version(),listOf(1,2)))
        for(u in 1L..2L) run(SettlementCommand.ChangeAttendance("t",u,AttendanceChange.SET_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300))
        run(SettlementCommand.FinishTraining("t",version()))
    }
    @Test fun `posted training and transfer changes include corrections and reversals and explain current balance`() {
        setup();training()
        run(SettlementCommand.SendOtherPayment("p",1,75),Access(-1,2))
        assertEquals(1,service.balanceHistory(owner).page.total)
        run(SettlementCommand.ReceivePayment("p"))
        run(SettlementCommand.EditPaymentAmount("p",service.transfer(owner,"p").version,100))
        run(SettlementCommand.CancelPayment("p",service.transfer(owner,"p").version))
        run(SettlementCommand.ReopenTraining("t",version()))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,400))
        run(SettlementCommand.FinishTraining("t",version()))
        run(SettlementCommand.ReopenTraining("t",version()))
        run(SettlementCommand.CancelTraining("t",version()))
        run(SettlementCommand.SendOtherPayment("waiting",1,999),Access(-1,3))
        val result=service.balanceHistory(owner)
        assertEquals(listOf(-200L,200L,-150L,100L,-25L,-75L,150L).map(Long::toBigInteger),result.page.items.map { it.amount })
        assertEquals(7,result.page.total);assertEquals(0,result.balance)
        assertEquals(BigInteger.ZERO,result.page.items.fold(BigInteger.ZERO) { sum,e->sum+e.amount })
        assertEquals(service.balances(owner)[1],result.balance)
        assertEquals(result.page.items.map { -it.amount },service.balanceHistory(Access(-1,2)).page.items.map { it.amount })
        assertEquals(0,service.balanceHistory(Access(-1,3,true)).page.total)
        assertTrue(result.page.items.filter { it.trainingId!=null }.all { it.trainingTitle=="Теннис" && it.trainingId=="t" })
        assertTrue(result.page.items.filter { it.transferId!=null }.all { it.transferId=="p" && it.from==2L && it.to==1L })
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }
    @Test fun `history pages are isolated by group and caller and do not load unrelated audit payloads`() {
        setup()
        for(i in 0..24) run(SettlementCommand.RecordAdminPayment("p$i",1,2,i+1L))
        run(SettlementCommand.RecordAdminPayment("p0",1,2,9999),Access(-2,1,true))
        queries.clear()
        val pages=(0..2).map { service.balanceHistory(owner,it) }
        assertTrue(queries.none { "after_json" in it || "before_json" in it || "payload_json" in it })
        assertTrue(queries.count { it.startsWith("SELECT") }<=12,"No SQL request per displayed row")
        assertEquals(listOf(10,10,5),pages.map { it.page.items.size })
        assertTrue(pages.all { it.page.total==25 && it.balance==325L })
        val entries=pages.flatMap { it.page.items }
        assertEquals(25,entries.map { it.actionId }.distinct().size)
        assertEquals((24 downTo 0).map { "p$it" },entries.map { it.transferId })
        assertEquals(pages[2],service.balanceHistory(owner,999))
        assertEquals(pages[0],service.balanceHistory(owner,-1))
        assertEquals(9999,service.balanceHistory(Access(-2,1)).balance)
        assertEquals(0,service.balanceHistory(Access(-1,3,true)).page.total)
        service.remember(Account(4,"Другой"));service.rememberMembership(-2,4,true)
        assertFailsWith<AccountingException> { service.balanceHistory(Access(-1,4,true)) }
    }
    @Test fun `large posted amounts are summed without intermediate long or SQLite overflow`() {
        setup()
        run(SettlementCommand.RecordAdminPayment("p1",2,1,Long.MAX_VALUE))
        run(SettlementCommand.RecordAdminPayment("p2",1,2,Long.MAX_VALUE))
        run(SettlementCommand.RecordAdminPayment("p3",1,2,Long.MAX_VALUE))
        val history=service.balanceHistory(owner)
        assertEquals(Long.MAX_VALUE,history.balance)
        assertEquals(listOf(1,1,-1).map { Long.MAX_VALUE.toBigInteger()*it.toBigInteger() },history.page.items.map { it.amount })
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }
    @Test fun `one correction is one net change and zero-effect training is omitted`() {
        setup()
        run(SettlementCommand.RecordAdminPayment("p",1,2,100))
        run(SettlementCommand.EditPaymentAmount("p",1,125))
        run(SettlementCommand.CreateTraining("zero","Без изменения баланса","2026-10-16","18:30"))
        run(SettlementCommand.AddPlayers("zero",version("zero"),listOf(1)))
        run(SettlementCommand.ChangeAttendance("zero",1,AttendanceChange.SET_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("zero",1,AttendanceChange.SET_PAID,100))
        run(SettlementCommand.FinishTraining("zero",version("zero")))
        val history=service.balanceHistory(owner)
        assertEquals(2,history.page.total);assertEquals(125,history.balance)
        assertEquals(listOf(25L,100L).map(Long::toBigInteger),history.page.items.map { it.amount })
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }
}
