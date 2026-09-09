package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class SettlementServiceTest {
    @TempDir lateinit var dir: Path
    private val admin = Access(-1, 1, true)
    private val member = Access(-1, 2)
    private val other = Access(-2, 3, true)
    private var serial = 0
    private fun setup(): SettlementService {
        val service = SettlementService(Database(dir.resolve("new.sqlite")))
        for (id in 1L..4L) service.remember(Account(id, "User $id"))
        service.register(SettlementGroup(-1, "First", "Europe/Moscow"))
        service.register(SettlementGroup(-2, "Second", "Europe/Moscow"))
        for (id in 1L..3L) service.rememberMembership(-1, id, true)
        for (id in 2L..4L) service.rememberMembership(-2, id, true)
        return service
    }
    private fun SettlementService.run(command: SettlementCommand, who: Access = admin) = execute(who, "test-${serial++}", command)
    private fun SettlementService.create(id: String = "t", who: Access = admin) = run(SettlementCommand.CreateTraining(id, "Теннис", "2026-09-09", "19:00"), who)
    private fun SettlementService.change(user: Long, change: AttendanceChange, value: Long = 0, who: Access = admin) = run(SettlementCommand.ChangeAttendance("t", user, change, value), who)
    private fun SettlementService.finish() = run(SettlementCommand.FinishTraining("t", training(admin, "t").version))
    private fun SettlementService.reopen() = run(SettlementCommand.ReopenTraining("t", training(admin, "t").version))
    private fun SettlementService.sample() {
        create()
        change(1, AttendanceChange.JOIN)
        change(2, AttendanceChange.JOIN)
        change(1, AttendanceChange.MARK_PAID)
        finish()
    }

    @Test fun `reopen keeps previous balances and attendance until close and cancellation keeps actual transfers`() {
        val s = setup(); s.sample()
        assertEquals(mapOf(1L to 150L, 2L to -150L), s.balances(admin))
        s.run(SettlementCommand.RecordTransfer("advance", 2, 1, 200, "2026-09-09"), member)
        val before = s.balances(admin)
        s.reopen()
        s.change(2, AttendanceChange.LEAVE, who = member)
        assertEquals(before, s.balances(admin))
        assertEquals(1, s.roster(admin).items.single { it.account.id == 2L }.attendance)
        s.finish()
        assertEquals(mapOf(1L to -200L, 2L to 200L), s.balances(admin))
        assertEquals(0, s.roster(admin).items.single { it.account.id == 2L }.attendance)
        s.run(SettlementCommand.CancelTraining("t", s.training(admin, "t").version))
        assertEquals(mapOf(1L to -200L, 2L to 200L), s.balances(admin))
        assertEquals(1, s.trainings(admin).total)
        assertTrue(s.database.verify().contains("целостность в порядке"))
    }

    @Test fun `membership and admin rights are scoped to group and ordinary members cannot change another player`() {
        val s = setup(); s.create(); s.create("elsewhere", other)
        assertFailsWith<AccountingException> { s.change(1, AttendanceChange.JOIN, who = member) }
        assertFailsWith<AccountingException> { s.create("forbidden", Access(-1, 3)) }
        s.run(SettlementCommand.SetAdministrator(2, true))
        assertTrue(s.isAdmin(member))
        assertFalse(s.isAdmin(Access(-2, 2)))
        assertFailsWith<AccountingException> { s.run(SettlementCommand.SetAdministrator(3, true), member) }
        s.change(1, AttendanceChange.JOIN, who = member)
        assertFailsWith<AccountingException> { s.training(admin, "elsewhere") }
        assertFailsWith<AccountingException> { s.run(SettlementCommand.CancelTraining("elsewhere", 1)) }
        assertFailsWith<AccountingException> { s.change(4, AttendanceChange.JOIN) }
        assertEquals(listOf("t"), s.trainings(member).items.map { it.id })
        assertEquals(listOf("elsewhere"), s.trainings(other).items.map { it.id })
        s.run(SettlementCommand.SetAdministrator(2, false))
        assertFalse(s.isAdmin(member))
        assertFailsWith<AccountingException> { s.change(1, AttendanceChange.LEAVE, who = member) }
    }

    @Test fun `idempotency belongs to request while repeated delta clicks accumulate`() {
        val s = setup(); s.create()
        s.change(2, AttendanceChange.JOIN, who = member)
        s.change(2, AttendanceChange.MARK_PAID, who = member)
        val delta = SettlementCommand.ChangeAttendance("t", 2, AttendanceChange.ADJUST_PAID, 50)
        val first = s.execute(member, "telegram:123", delta)
        assertEquals(first, s.execute(member, "telegram:123", delta))
        s.execute(member, "telegram:124", delta)
        s.change(2, AttendanceChange.MARK_PAID, who = member)
        s.change(2, AttendanceChange.ADJUST_MINUTES, 30, member)
        s.change(2, AttendanceChange.JOIN, who = member)
        val row = s.training(member, "t").players.single()
        assertEquals(400, row.paid)
        assertEquals(90, row.minutes)
        assertFailsWith<AccountingException> { s.execute(member, "telegram:123", delta.copy(value = -50)) }
        assertFailsWith<AccountingException> { s.execute(admin, "telegram:123", delta) }
    }

    @Test fun `concurrent updates are serialized and close rejects a stale preview`() {
        val s = setup(); s.create(); s.change(2, AttendanceChange.JOIN, who = member)
        val version = s.training(admin, "t").version
        Executors.newFixedThreadPool(4).use { pool ->
            val jobs = (1..20).map { index -> Callable {
                s.execute(member, "concurrent:$index", SettlementCommand.ChangeAttendance("t", 2, AttendanceChange.ADJUST_PAID, 50))
            } }
            pool.invokeAll(jobs).forEach { it.get() }
        }
        assertEquals(1000, s.training(admin, "t").players.single().paid)
        assertFailsWith<AccountingException> { s.run(SettlementCommand.FinishTraining("t", version)) }
        assertEquals(emptyMap(), s.balances(admin))
        s.finish()
        assertEquals(TrainingPhase.CLOSED, s.training(admin, "t").phase)
        assertFailsWith<AccountingException> { s.change(2, AttendanceChange.ADJUST_PAID, 50, member) }
    }

    @Test fun `transfer review removes money and only its initiator may resolve it`() {
        val s = setup()
        s.run(SettlementCommand.RecordTransfer("p", 2, 1, 300, "2026-09-09"), member)
        assertFailsWith<AccountingException> { s.run(SettlementCommand.RecordTransfer("foreign", 2, 1, 300, "2026-09-09"), Access(-1, 3)) }
        assertFailsWith<DuplicateTransfer> { s.run(SettlementCommand.RecordTransfer("copy", 2, 1, 300, "2026-09-09"), member) }
        s.run(SettlementCommand.ChangeTransfer("p", 1, TransferChange.REVIEW))
        assertEquals(mapOf(1L to 0L, 2L to 0L), s.balances(admin))
        assertFailsWith<AccountingException> { s.run(SettlementCommand.ChangeTransfer("p", 2, TransferChange.CONFIRM), member) }
        s.run(SettlementCommand.ChangeTransfer("p", 2, TransferChange.CONFIRM))
        assertEquals(mapOf(1L to -300L, 2L to 300L), s.balances(admin))
        s.run(SettlementCommand.ChangeTransfer("p", 3, TransferChange.REVIEW), member)
        s.run(SettlementCommand.ChangeTransfer("p", 4, TransferChange.CANCEL), member)
        assertEquals(mapOf(1L to 0L, 2L to 0L), s.balances(admin))
        assertFailsWith<AccountingException> { s.transfer(other, "p") }
        assertEquals(0, s.transfers(other).total)
    }

    @Test fun `representation requires current absence proof for the same group`() {
        val s = setup()
        val command = SettlementCommand.RecordTransfer("represented", 2, 3, 100, "2026-09-09", onBehalfOf = 3)
        assertFailsWith<AccountingException> { s.run(command) }
        assertFailsWith<AccountingException> { s.execute(admin, "absent-other", command, AbsentAccount(-2, 3)) }
        s.execute(admin, "absent-correct", command, AbsentAccount(-1, 3))
        assertEquals(1, s.transfer(admin, "represented").createdBy)
    }

    @Test fun `payer can leave while guest stays attached to inviter and rounding is stable`() {
        val s = setup(); s.create()
        s.change(1, AttendanceChange.JOIN)
        s.change(1, AttendanceChange.MARK_PAID)
        s.change(1, AttendanceChange.LEAVE)
        s.change(2, AttendanceChange.JOIN)
        s.change(2, AttendanceChange.GUEST, 1)
        s.change(3, AttendanceChange.JOIN)
        s.finish()
        assertEquals(mapOf(1L to 300L, 2L to -200L, 3L to -100L), s.balances(admin))
        assertEquals(3, s.training(admin, "t").players.size)
        assertEquals(0, s.roster(admin).items.single { it.account.id == 1L }.attendance)
        s.reopen(); s.finish()
        assertEquals(mapOf(1L to 300L, 2L to -200L, 3L to -100L), s.balances(admin))
    }

    @Test fun `failed financial overflow rolls back the record audit and ledger together`() {
        val s = setup()
        s.run(SettlementCommand.RecordTransfer("max", 2, 1, Long.MAX_VALUE, "2026-09-09"), member)
        val before = s.history(admin).total
        assertFailsWith<AccountingException> { s.run(SettlementCommand.RecordTransfer("overflow", 2, 1, 1, "2026-09-09"), member) }
        assertEquals(before, s.history(admin).total)
        assertFailsWith<AccountingException> { s.transfer(admin, "overflow") }
        assertEquals(mapOf(1L to -Long.MAX_VALUE, 2L to Long.MAX_VALUE), s.balances(admin))
        s.database.verify()
    }

    @Test fun `lists are paged and zero balances appear only for previously playing accounts`() {
        val s = setup()
        assertEquals(0, s.roster(admin, balanceOnly = true, settled = true).total)
        s.sample()
        s.run(SettlementCommand.RecordTransfer("settled", 2, 1, 150, "2026-09-09"), member)
        assertEquals(2, s.roster(admin, balanceOnly = true, settled = true).total)
        assertEquals(0, s.roster(admin, balanceOnly = true).total)
        for (i in 1..20) s.create("t$i")
        assertEquals(8, s.trainings(admin).items.size)
        assertEquals(5, s.trainings(admin, page = 2).items.size)
        assertEquals(2, s.trainings(admin, page = Int.MAX_VALUE).index)
        assertEquals(1, s.trainings(member, mine = true).total)
        val first = s.history(admin)
        val second = s.history(admin, page = 1)
        assertEquals(8, first.items.size)
        assertTrue(first.items.map { it.id }.intersect(second.items.map { it.id }.toSet()).isEmpty())
    }

    @Test fun `group settings survive Telegram refresh and foreign keys reject cross-group rows`() {
        val s = setup()
        s.database.write { c -> sqlUpdate(c, "UPDATE group_users SET is_attending=0,nickname='Рома' WHERE group_id=-1 AND user_id=2") }
        s.remember(Account(2, "Changed", username = "new_name"))
        s.rememberMembership(-1, 2, false)
        s.database.read { c ->
            assertEquals(listOf(Triple(false, "Рома", false), Triple(true, null, true)), sqlQuery(c,
                "SELECT is_attending,nickname,present FROM group_users WHERE user_id=2 ORDER BY group_id DESC") {
                Triple(it.getBoolean(1), it.getString(2), it.getBoolean(3))
            })
        }
        s.create()
        assertFailsWith<SQLException> { s.database.write { c -> sqlUpdate(c,
            "INSERT INTO training_players(group_id,training_id,user_id,playing,ordinal) VALUES(-1,'t',4,1,0)") } }
        val copy = s.database.backup(dir.resolve("copy.sqlite"))
        assertEquals(s.database.verify(), Database(copy).verify())
    }
}
