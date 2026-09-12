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
    private fun SettlementService.joinForHour(user:Long,who:Access=admin) {
        change(user,AttendanceChange.JOIN,who=who)
        change(user,AttendanceChange.SET_MINUTES,60,who)
    }
    private fun SettlementService.finish() = run(SettlementCommand.FinishTraining("t", training(admin, "t").version))
    private fun SettlementService.reopen() = run(SettlementCommand.ReopenTraining("t", training(admin, "t").version))
    private fun SettlementService.sample() {
        create()
        joinForHour(1)
        joinForHour(2)
        change(1, AttendanceChange.MARK_PAID)
        finish()
    }

    @Test fun `bulk addition is atomic group scoped admin only and replay safe`() {
        val s=setup();s.create()
        val command=SettlementCommand.AddPlayers("t",1,listOf(1,2,3))
        assertFailsWith<AccountingException> { s.run(command,member) }
        assertFailsWith<AccountingException> { s.run(SettlementCommand.AddPlayers("t",1,listOf(1,4))) }
        assertTrue(s.training(admin,"t").players.isEmpty())
        val receipt=s.execute(admin,"bulk",command)
        assertEquals(receipt,s.execute(admin,"bulk",command))
        val t=s.training(admin,"t")
        assertEquals(2,t.version)
        assertEquals(3,t.players.size)
        assertTrue(t.players.all { it.playing && it.minutes==0L && it.paid==0L && it.guestMinutes==0L })
        assertEquals(2,s.history(admin).total)
        assertTrue(s.balances(admin).values.all { it==0L })
        assertEquals(0,s.trainings(other).total)
    }

    @Test fun `bulk addition preserves existing players and rejects stale or closed training`() {
        val s=setup();s.create();s.joinForHour(2);s.change(2,AttendanceChange.SET_PAID,600)
        s.change(2,AttendanceChange.SET_MINUTES,90)
        val before=s.training(admin,"t")
        assertFailsWith<AccountingException> { s.run(SettlementCommand.AddPlayers("t",1,listOf(1,2))) }
        s.run(SettlementCommand.AddPlayers("t",before.version,listOf(1,2)))
        assertEquals(before.players.single(),s.training(admin,"t").players.single { it.userId==2L })
        s.finish()
        assertFailsWith<AccountingException> { s.run(SettlementCommand.AddPlayers("t",s.training(admin,"t").version,listOf(3))) }
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

    @Test fun `restoring cancelled training preserves input and transfers until explicitly accounted without duplicates`() {
        val s=setup();s.sample()
        s.run(SettlementCommand.RecordTransfer("advance",2,1,200,"2026-09-09"),member)
        val accounted=s.balances(admin)
        s.run(SettlementCommand.CancelTraining("t",s.training(admin,"t").version))
        val cancelled=s.training(admin,"t")
        val transferOnly=s.balances(admin)
        assertEquals(mapOf(1L to -200L,2L to 200L),transferOnly)
        assertFalse(s.roster(admin).items.single { it.account.id==2L }.hasPlayed)
        val command=SettlementCommand.RestoreTraining("t",cancelled.version)
        val receipt=s.execute(admin,"restore",command)
        assertEquals(receipt,s.execute(admin,"restore",command))
        val restored=s.training(admin,"t")
        assertEquals(cancelled.copy(phase=TrainingPhase.OPEN,version=cancelled.version+1),restored)
        assertEquals(0,restored.appliedVersion)
        assertEquals(transferOnly,s.balances(admin))
        assertTrue(s.roster(admin).items.single { it.account.id==2L }.hasPlayed)
        assertEquals(0,s.roster(admin).items.single { it.account.id==2L }.attendance)
        assertEquals(1,s.history(admin,trainingId="t").items.count { it.kind=="RestoreTraining" })
        assertFailsWith<AccountingException> { s.run(command) }
        assertFailsWith<AccountingException> { s.run(command.copy(version=restored.version)) }
        s.finish()
        assertEquals(accounted,s.balances(admin))
        assertEquals(1,s.roster(admin).items.single { it.account.id==2L }.attendance)
        assertEquals(1,s.trainings(admin).total)
        assertTrue(s.database.verify().contains("целостность в порядке"))
    }

    @Test fun `restore is group scoped admin only and works after cancelling an open or edited training`() {
        val s=setup();s.create();s.joinForHour(1)
        s.run(SettlementCommand.CancelTraining("t",s.training(admin,"t").version))
        val cancelled=s.training(admin,"t")
        val command=SettlementCommand.RestoreTraining("t",cancelled.version)
        assertFailsWith<AccountingException> { s.run(command,member) }
        assertFailsWith<AccountingException> { s.run(command,Access(-1,3)) } // Admin only in the other group.
        assertFailsWith<AccountingException> { s.run(command,other) }
        assertEquals(cancelled,s.training(admin,"t"))
        s.run(SettlementCommand.SetAdministrator(2,true))
        s.run(command,member)
        s.change(1,AttendanceChange.MARK_PAID)
        s.joinForHour(2)
        s.finish();s.reopen();s.change(1,AttendanceChange.SET_PAID,600)
        s.run(SettlementCommand.CancelTraining("t",s.training(admin,"t").version))
        s.run(SettlementCommand.RestoreTraining("t",s.training(admin,"t").version),member)
        assertTrue(s.balances(admin).values.all { it==0L })
        assertEquals(600,s.training(admin,"t").players.single { it.userId==1L }.paid)
        s.finish()
        assertEquals(mapOf(1L to 300L,2L to -300L),s.balances(admin))
    }

    @Test fun `members create trainings and only the creator or this groups admin can finish`() {
        val s=setup();s.create(who=member)
        assertEquals(2,s.training(member,"t").createdBy)
        assertEquals(1,s.trainings(member,mine=true).total)
        s.joinForHour(2,member);s.change(2,AttendanceChange.SET_PAID,300,member)
        val t=s.training(member,"t")
        assertTrue(s.canFinish(member,t));assertTrue(s.canFinish(admin,t))
        assertFalse(s.canFinish(Access(-1,3),t));assertFalse(s.canFinish(other,t))
        val finish=SettlementCommand.FinishTraining("t",t.version)
        val before=s.history(member).total
        assertEquals(ErrorCode.FORBIDDEN,assertFailsWith<AccountingException> { s.run(finish,Access(-1,3)) }.code)
        assertFailsWith<AccountingException> { s.run(finish,other) }
        assertEquals(before,s.history(member).total)
        assertFailsWith<AccountingException> { s.run(SettlementCommand.EditTraining("t",t.version,"Правка","2026-09-09","19:00"),member) }
        s.run(finish,member)
        assertEquals(TrainingPhase.CLOSED,s.training(member,"t").phase)
        s.run(SettlementCommand.ReopenTraining("t",s.training(admin,"t").version))
        s.run(SettlementCommand.SetAdministrator(3,true))
        s.run(SettlementCommand.FinishTraining("t",s.training(member,"t").version),Access(-1,3))
        assertEquals(TrainingPhase.CLOSED,s.training(member,"t").phase)
    }

    @Test fun `departed creator cannot create or finish and another groups appointment grants no rights`() {
        val s=setup();s.create(who=member);s.joinForHour(2,member);s.change(2,AttendanceChange.SET_PAID,300,member)
        val t=s.training(member,"t")
        s.run(SettlementCommand.SetAdministrator(3,true),other)
        assertFalse(s.canFinish(Access(-1,3),t))
        s.rememberMembership(-1,2,false)
        assertFalse(s.canFinish(member,t))
        assertFailsWith<AccountingException> { s.create("left",member) }
        assertEquals(ErrorCode.FORBIDDEN,assertFailsWith<AccountingException> { s.run(SettlementCommand.FinishTraining("t",t.version),member) }.code)
        s.run(SettlementCommand.FinishTraining("t",t.version),admin)
        assertEquals(TrainingPhase.CLOSED,s.training(admin,"t").phase)
    }

    @Test fun `default start time permissions are scoped and concurrent edits are rejected`() {
        val s=setup();assertEquals("18:30",s.group(-1).defaultStartTime)
        assertFailsWith<AccountingException> { s.run(SettlementCommand.SetDefaultStartTime("20:00","18:30"),member) }
        s.run(SettlementCommand.SetAdministrator(2,true))
        s.run(SettlementCommand.SetDefaultStartTime("20:00","18:30"),member)
        assertFailsWith<AccountingException> { s.run(SettlementCommand.SetDefaultStartTime("21:00","18:30"),admin) }
        assertFailsWith<AccountingException> { s.run(SettlementCommand.SetDefaultStartTime("21:00","18:30"),Access(-2,2)) }
        s.register(SettlementGroup(-1,"New title","Europe/Moscow"))
        assertEquals("20:00",s.group(-1).defaultStartTime);assertEquals("18:30",s.group(-2).defaultStartTime)
        assertTrue(s.balances(admin).isEmpty())
    }

    @Test fun `membership and admin rights are scoped to group and ordinary members cannot change another player`() {
        val s = setup(); s.create(); s.create("elsewhere", other)
        assertFailsWith<AccountingException> { s.joinForHour(1, member) }
        assertFailsWith<AccountingException> { s.create("forbidden", Access(-1, 4)) }
        s.run(SettlementCommand.SetAdministrator(2, true))
        assertTrue(s.isAdmin(member))
        assertFalse(s.isAdmin(Access(-2, 2)))
        assertFailsWith<AccountingException> { s.run(SettlementCommand.SetAdministrator(3, true), member) }
        s.joinForHour(1, member)
        assertFailsWith<AccountingException> { s.training(admin, "elsewhere") }
        assertFailsWith<AccountingException> { s.run(SettlementCommand.CancelTraining("elsewhere", 1)) }
        assertFailsWith<AccountingException> { s.joinForHour(4) }
        assertEquals(listOf("t"), s.trainings(member).items.map { it.id })
        assertEquals(listOf("elsewhere"), s.trainings(other).items.map { it.id })
        s.run(SettlementCommand.SetAdministrator(2, false))
        assertFalse(s.isAdmin(member))
        assertFailsWith<AccountingException> { s.change(1, AttendanceChange.LEAVE, who = member) }
    }

    @Test fun `idempotency belongs to request while repeated delta clicks accumulate`() {
        val s = setup(); s.create()
        s.joinForHour(2, member)
        s.change(2, AttendanceChange.MARK_PAID, who = member)
        val delta = SettlementCommand.ChangeAttendance("t", 2, AttendanceChange.ADJUST_PAID, 50)
        val first = s.execute(member, "telegram:123", delta)
        assertEquals(first, s.execute(member, "telegram:123", delta))
        s.execute(member, "telegram:124", delta)
        s.change(2, AttendanceChange.MARK_PAID, who = member)
        s.change(2, AttendanceChange.ADJUST_MINUTES, 30, member)
        s.change(2,AttendanceChange.JOIN,who=member)
        val row = s.training(member, "t").players.single()
        assertEquals(400, row.paid)
        assertEquals(90, row.minutes)
        assertFailsWith<AccountingException> { s.execute(member, "telegram:123", delta.copy(value = -50)) }
        assertFailsWith<AccountingException> { s.execute(admin, "telegram:123", delta) }
    }

    @Test fun `concurrent updates are serialized and close rejects a stale preview`() {
        val s = setup(); s.create(); s.joinForHour(2, member)
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

    @Test fun `leaving keeps payment and guest allocation stays stable`() {
        val s = setup(); s.create()
        s.joinForHour(1)
        s.change(1, AttendanceChange.MARK_PAID)
        s.change(1, AttendanceChange.LEAVE)
        assertEquals(300,s.training(admin,"t").players.single().paid)
        s.change(1, AttendanceChange.LEAVE_AND_CLEAR_PAYMENT,300)
        s.joinForHour(2)
        s.change(2, AttendanceChange.GUEST, 1)
        s.joinForHour(3)
        s.change(2, AttendanceChange.SET_PAID,300)
        s.finish()
        assertEquals(mapOf(2L to 100L, 3L to -100L), s.balances(admin))
        assertEquals(3, s.training(admin, "t").players.size)
        assertEquals(0, s.roster(admin).items.single { it.account.id == 1L }.attendance)
        s.reopen(); s.finish()
        assertEquals(mapOf(2L to 100L, 3L to -100L), s.balances(admin))
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

    @Test fun `payment can survive leaving and stale removal cannot clear a changed payment`() {
        val s=setup();s.create()
        for (change in listOf(AttendanceChange.MARK_PAID,AttendanceChange.ADJUST_PAID)) {
            assertFailsWith<AccountingException> { s.change(2,change,50,member) }
        }
        s.joinForHour(2, member)
        s.change(2,AttendanceChange.MARK_PAID,who=member)
        s.change(2,AttendanceChange.ADJUST_MINUTES,30,member)
        s.change(2,AttendanceChange.ADJUST_PAID,50,member)
        assertFailsWith<AccountingException> { s.change(2,AttendanceChange.LEAVE_AND_CLEAR_PAYMENT,300,member) }
        assertEquals(350,s.training(member,"t").players.single().paid)
        s.change(2,AttendanceChange.LEAVE_AND_CLEAR_PAYMENT,350,member)
        s.joinForHour(2, member)
        assertEquals(60,s.training(member,"t").players.single().minutes)
        assertEquals(0,s.training(member,"t").players.single().paid)
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
    @Test fun `amount edit preserves review ownership and does not create a second transfer`() {
        val s=setup()
        s.run(SettlementCommand.RecordTransfer("payment",1,2,300,"2026-09-10"))
        s.run(SettlementCommand.EditTransferAmount("payment",1,450),member)
        assertEquals(mapOf(1L to 450L,2L to -450L),s.balances(admin))
        assertFailsWith<AccountingException> { s.run(SettlementCommand.EditTransferAmount("payment",1,500)) }
        assertFailsWith<AccountingException> { s.run(SettlementCommand.EditTransferAmount("payment",2,500),Access(-1,3,true)) }
        s.run(SettlementCommand.ChangeTransfer("payment",2,TransferChange.REVIEW))
        s.run(SettlementCommand.EditTransferAmount("payment",3,600),member)
        assertTrue(s.balances(admin).values.all { it==0L })
        assertFailsWith<AccountingException> { s.run(SettlementCommand.ChangeTransfer("payment",4,TransferChange.CONFIRM),member) }
        s.run(SettlementCommand.ChangeTransfer("payment",4,TransferChange.CONFIRM))
        assertEquals(mapOf(1L to 600L,2L to -600L),s.balances(admin))
        assertEquals(1,s.transfers(member).total)
        val count=s.history(admin).total
        s.run(SettlementCommand.EditTransferAmount("payment",5,600),member)
        assertEquals(count,s.history(admin).total)
    }

    @Test fun `duplicates use a rolling 24 hour window regardless of recorder or stated date`() {
        val db=setup().database
        val start=java.time.Instant.parse("2026-09-10T21:00:00Z")
        fun at(seconds:Long)=SettlementService(db,java.time.Clock.fixed(start.plusSeconds(seconds),java.time.ZoneOffset.UTC))
        at(0).execute(admin,"first",SettlementCommand.RecordTransfer("first",1,2,300,"2026-09-10"))
        assertFailsWith<DuplicateTransfer> { at(23*3600).execute(member,"second",SettlementCommand.RecordTransfer("second",1,2,300,"2026-09-11")) }
        at(86401).execute(member,"second",SettlementCommand.RecordTransfer("second",1,2,300,"2026-09-10"))
        at(86402).execute(member,"third",SettlementCommand.RecordTransfer("third",1,2,400,"2026-09-10"))
        assertFailsWith<DuplicateTransfer> { at(86403).execute(admin,"edit",SettlementCommand.EditTransferAmount("third",1,300)) }
        at(86403).execute(admin,"edit",SettlementCommand.EditTransferAmount("third",1,300,true))
        assertEquals(3,at(86404).transfers(member).total)
    }

    @Test fun `cached attendance migration preserves records and includes open participation in zero balances`() {
        val s=setup();s.sample()
        val before=s.training(admin,"t");val balances=s.balances(admin)
        s.database.write { c ->
            sqlUpdate(c,"ALTER TABLE groups DROP COLUMN default_start_time")
            sqlUpdate(c,"ALTER TABLE bot_sessions DROP COLUMN panel_json")
            sqlUpdate(c,"ALTER TABLE bot_deliveries DROP COLUMN pin_status")
            sqlUpdate(c,"ALTER TABLE bot_deliveries DROP COLUMN display_page")
            sqlUpdate(c,"ALTER TABLE group_users DROP COLUMN attendance_count")
            sqlUpdate(c,"ALTER TABLE group_users DROP COLUMN has_played")
            c.createStatement().use { it.execute("PRAGMA user_version=1") }
        }
        val migrated=SettlementService(Database(s.database.path))
        assertEquals(before,migrated.training(admin,"t"));assertEquals(balances,migrated.balances(admin))
        assertEquals(1,migrated.roster(admin).items.single { it.account.id==2L }.attendance)
        migrated.execute(admin,"new",SettlementCommand.CreateTraining("new","Теннис","2026-09-10","19:00"))
        migrated.execute(admin,"join",SettlementCommand.AddPlayers("new",1,listOf(3)))
        assertTrue(migrated.roster(admin,balanceOnly=true,settled=true).items.any { it.account.id==3L })
        assertTrue(migrated.database.verify().contains("целостность в порядке"))
    }

}
