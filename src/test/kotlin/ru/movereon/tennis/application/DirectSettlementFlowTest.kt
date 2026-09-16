package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import java.nio.file.Path
import kotlin.test.*

class DirectSettlementFlowTest {
    @TempDir lateinit var dir: Path
    @Test fun `all members see parts of one direct plan while pending payments reserve only their group`() {
        val service = SettlementService(Database(dir.resolve("bot.sqlite")))
        for (user in 1L..5L) service.remember(Account(user, "Игрок $user"))
        for (group in listOf(-1L,-2L)) {
            val admin = Access(group, 1, true)
            service.register(SettlementGroup(group, "Группа", "Europe/Moscow"))
            for (user in 1L..5L) service.rememberMembership(group, user, true)
            var seq = 0
            fun command(c: SettlementCommand) = service.execute(admin, "setup${seq++}", c)
            command(SettlementCommand.CreateTraining("t", "Теннис", "2026-09-16", "18:30"))
            command(SettlementCommand.AddPlayers("t", 1, listOf(1,2,3,4,5)))
            listOf(30L,30L,210L,150L,90L).forEachIndexed { i, minutes -> command(SettlementCommand.ChangeAttendance("t",i+1L,AttendanceChange.SET_MINUTES,minutes)) }
            command(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,900))
            command(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_PAID,800))
            command(SettlementCommand.FinishTraining("t", service.training(admin,"t").version))
        }
        fun plan(group: Long) = (1L..5L).flatMap { user -> service.paymentSuggestions(Access(group,user)).items }
            .map { Triple(it.from.value.toLong(),it.to.value.toLong(),it.amount) }.toSet()
        val expected = setOf(Triple(3L,2L,700L), Triple(4L,1L,500L), Triple(5L,1L,300L))
        assertEquals(expected, plan(-1)); assertEquals(expected, plan(-2))
        val before = service.balances(Access(-1,1))
        assertFailsWith<AccountingException> { service.execute(Access(-1,3),"old",SettlementCommand.SendPayment("old",1,700)) }
        service.execute(Access(-1,3),"send",SettlementCommand.SendPayment("payment",2,700))
        assertEquals(before, service.balances(Access(-1,1)))
        assertEquals(expected - Triple(3L,2L,700L), plan(-1)); assertEquals(expected, plan(-2))
        service.execute(Access(-1,2),"receive",SettlementCommand.ReceivePayment("payment"))
        assertEquals(0L, service.balances(Access(-1,1))[3L]); assertEquals(0L, service.balances(Access(-1,1))[2L])
        assertEquals(expected - Triple(3L,2L,700L), plan(-1)); assertEquals(expected, plan(-2))
    }
}
