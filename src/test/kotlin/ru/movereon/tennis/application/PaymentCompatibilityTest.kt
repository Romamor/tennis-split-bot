package ru.movereon.tennis.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import java.nio.file.Path
import kotlin.test.*

class PaymentCompatibilityTest {
    @TempDir lateinit var dir:Path
    private val sender=Access(-1,1)
    private val recipient=Access(-1,2)
    private var sequence=0
    private fun setup():SettlementService=SettlementService(Database(dir.resolve("payments.sqlite"))).also { service ->
        service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        (1L..3L).forEach { service.remember(Account(it,"Игрок $it"));service.rememberMembership(-1,it,true) }
    }
    private fun SettlementService.run(command:SettlementCommand,actor:Access=sender)=execute(actor,"action:${sequence++}",command)

    @Test fun `serialized legacy commands preserve their financial lifecycle and history`() {
        val service=setup()
        fun restored(command:SettlementCommand)=Json.decodeFromString<SettlementCommand>(Json.encodeToString(command))
        val original:SettlementCommand=SettlementCommand.RecordTransfer("old",1,2,300,"2026-01-02","Сохранённое примечание")
        val receipt=service.execute(sender,"persisted-plan",restored(original))
        assertEquals(receipt,service.execute(sender,"persisted-plan",restored(original)))
        assertEquals(mapOf(1L to 300L,2L to -300L),service.balances(sender))
        service.run(restored(SettlementCommand.ChangeTransfer("old",1,TransferChange.REVIEW)),recipient)
        assertTrue(service.balances(sender).values.all { it==0L })
        service.run(restored(SettlementCommand.EditTransferAmount("old",2,400)))
        val reviewing=service.transfer(sender,"old")
        assertEquals(2,reviewing.reviewer);assertEquals(2,reviewing.reviewParty)
        assertEquals("2026-01-02",reviewing.date);assertEquals("Сохранённое примечание",reviewing.note)
        assertFailsWith<AccountingException> { service.run(restored(SettlementCommand.ChangeTransfer("old",3,TransferChange.CONFIRM))) }
        service.run(restored(SettlementCommand.ChangeTransfer("old",3,TransferChange.CONFIRM)),recipient)
        assertEquals(mapOf(1L to 400L,2L to -400L),service.balances(sender))
        assertEquals(listOf("ChangeTransfer","EditTransferAmount","ChangeTransfer","RecordTransfer"),service.history(sender,transferId="old").items.map { it.kind })
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }

    @Test fun `current and legacy edits use equivalent posted transitions without changing permissions`() {
        val service=setup();val admin=Access(-1,3,true)
        service.run(SettlementCommand.RecordTransfer("old",1,2,100,"2026-01-02"))
        service.run(SettlementCommand.RecordAdminPayment("new",1,2,100),admin)
        assertFailsWith<AccountingException> { service.run(SettlementCommand.EditTransferAmount("new",1,200)) }
        assertFailsWith<AccountingException> { service.run(SettlementCommand.EditPaymentAmount("new",1,200)) }
        service.run(SettlementCommand.EditTransferAmount("old",1,200),recipient)
        service.run(SettlementCommand.EditPaymentAmount("new",1,200),admin)
        assertEquals(mapOf(1L to 400L,2L to -400L),service.balances(sender))
        val history=service.history(sender).total
        assertEquals(0,service.run(SettlementCommand.EditTransferAmount("old",2,200),recipient).id)
        assertEquals(0,service.run(SettlementCommand.EditPaymentAmount("new",2,200),admin).id)
        assertEquals(history,service.history(sender).total)
        service.run(SettlementCommand.ChangeTransfer("old",2,TransferChange.REVIEW))
        service.run(SettlementCommand.ChangeTransfer("old",3,TransferChange.CANCEL))
        service.run(SettlementCommand.CancelPayment("new",2),admin)
        assertTrue(service.balances(sender).values.all { it==0L })
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }
}
