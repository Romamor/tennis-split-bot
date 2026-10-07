package ru.movereon.tennis.storage

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import kotlin.test.*

class FinancialVerificationTest {
    @TempDir lateinit var dir:Path
    private var serial=0
    private val admin=Access(-1,1,true)
    private fun seed():SettlementService {
        val s=SettlementService(Database(dir.resolve("ledger.sqlite")))
        for(user in 1L..4L) s.remember(Account(user,"Игрок $user"))
        for(group in listOf(-1L,-2L)) {
            s.register(SettlementGroup(group,"Тест","Europe/Moscow"))
            for(user in 1L..4L) s.rememberMembership(group,user,true)
        }
        return s
    }
    private fun SettlementService.run(command:SettlementCommand,a:Access=admin)=execute(a,"request-${serial++}",command)
    private fun SettlementService.transfer()=run(SettlementCommand.RecordAdminPayment("p",1,2,300))
    private fun SettlementService.training(a:Access=admin) {
        run(SettlementCommand.CreateTraining("t","Теннис","2026-10-08","18:30"),a)
        for(user in 1L..3L) {
            run(SettlementCommand.ChangeAttendance("t",user,AttendanceChange.JOIN),a)
            run(SettlementCommand.ChangeAttendance("t",user,AttendanceChange.SET_MINUTES,user*30),a)
        }
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_GUEST_MINUTES,30),a)
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,701),a)
        run(SettlementCommand.FinishTraining("t",training(a,"t").version),a)
    }
    private fun SettlementService.corrupt(sql:String)=database.write { sqlUpdate(it,sql) }
    private fun SettlementService.rejects(part:String) {
        val failure=assertFailsWith<IllegalStateException> { database.verify() }
        assertContains(failure.message.orEmpty(),part)
    }

    @Test fun `valid lifecycle reconciles guests rounding edits reversals and identical ids in different groups`() {
        val s=seed();s.training();s.training(Access(-2,1,true));s.transfer()
        assertContains(s.database.verify(),"финансовая сверка: 2 тренировок, 1 переводов")
        s.run(SettlementCommand.EditPaymentAmount("p",1,125))
        s.run(SettlementCommand.CancelPayment("p",2))
        s.run(SettlementCommand.SendOtherPayment("pending",2,50))
        assertContains(s.database.verify(),"целостность в порядке")
        s.run(SettlementCommand.ReopenTraining("t",s.training(admin,"t").version))
        assertContains(s.database.verify(),"целостность в порядке")
        s.run(SettlementCommand.CancelTraining("t",s.training(admin,"t").version))
        assertContains(s.database.verify(),"целостность в порядке")
        s.run(SettlementCommand.RestoreTraining("t",s.training(admin,"t").version))
        s.run(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_MINUTES,120))
        s.run(SettlementCommand.FinishTraining("t",s.training(admin,"t").version))
        assertContains(s.database.verify(),"целостность в порядке")
    }
    @Test fun `balanced wrong transfer amount is detected`() {
        val s=seed();s.transfer();s.corrupt("UPDATE balance_entries SET amount=amount*2")
        s.rejects("Перевод p, группа -1: участник 1, ожидается 300 ₽, проводки 600 ₽")
    }
    @Test fun `balanced wrong recipient is detected`() {
        val s=seed();s.transfer();s.corrupt("UPDATE balance_entries SET user_id=3 WHERE user_id=2")
        s.rejects("участник 2, ожидается -300 ₽, проводки 0 ₽")
    }
    @Test fun `missing complete balanced operation is detected`() {
        val s=seed();s.transfer();s.corrupt("DELETE FROM balance_entries")
        s.rejects("ожидается 300 ₽, проводки 0 ₽")
    }
    @Test fun `pending transfer cannot have an applied financial effect`() {
        val s=seed();s.transfer()
        s.corrupt("UPDATE transfers SET status='REVIEW',reviewer=2,review_party=2 WHERE id='p'")
        s.rejects("ожидается 0 ₽, проводки 300 ₽")
    }
    @Test fun `cancelled confirmed transfer must have its effect reversed`() {
        val s=seed();s.transfer();s.corrupt("UPDATE transfers SET status='CANCELLED'")
        s.rejects("ожидается 0 ₽, проводки 300 ₽")
    }
    @Test fun `training ledger must match recorded player time and rounded costs`() {
        val s=seed();s.training();s.corrupt("UPDATE training_players SET minutes=120 WHERE group_id=-1 AND user_id=2")
        s.rejects("Тренировка t, группа -1")
    }
    @Test fun `accounted training must have its entries even when another source hides their loss in group totals`() {
        val s=seed();s.training();s.transfer()
        s.corrupt("DELETE FROM balance_entries WHERE action_id IN (SELECT id FROM actions WHERE training_id='t')")
        s.rejects("Тренировка t, группа -1")
    }
    @Test fun `open training must not retain accounted entries`() {
        val s=seed();s.training();s.corrupt("UPDATE trainings SET status='OPEN',applied_version=0")
        s.rejects("ожидается 0 ₽")
    }
    @Test fun `closed training cannot pretend an older version was accounted`() {
        val s=seed();s.training();s.corrupt("UPDATE trainings SET applied_version=applied_version-1")
        s.rejects("учтённая версия не соответствует статусу CLOSED")
    }
    @Test fun `disabled time uses stored training rules even if group settings have changed`() {
        val s=seed();s.training()
        s.run(SettlementCommand.ReopenTraining("t",s.training(admin,"t").version))
        s.corrupt("UPDATE trainings SET track_time=0 WHERE group_id=-1")
        s.run(SettlementCommand.FinishTraining("t",s.training(admin,"t").version))
        s.corrupt("UPDATE groups SET track_time=1,guests_enabled=0 WHERE id=-1")
        assertContains(s.database.verify(),"целостность в порядке")
    }
    @Test fun `verification and rejected backup never repair or alter corrupted source`() {
        val s=seed();s.transfer();s.corrupt("UPDATE balance_entries SET amount=amount*2")
        val before=Files.readAllBytes(s.database.path)
        val readonly=Database(s.database.path,readOnly=true)
        assertFailsWith<IllegalStateException> { readonly.verify() }
        val target=dir.resolve("copy.sqlite")
        assertFailsWith<IllegalStateException> { readonly.backup(target) }
        assertFalse(Files.exists(target))
        assertContentEquals(before,Files.readAllBytes(s.database.path))
        Files.list(dir).use { files -> assertTrue(files.noneMatch { it.fileName.toString().startsWith(".tennis-backup-") }) }
    }
    @Test fun `balanced entries cannot be attached to a nonfinancial action`() {
        val s=seed();s.transfer();s.corrupt("UPDATE actions SET transfer_id=NULL")
        s.rejects("не связана ровно с одной тренировкой или переводом")
    }
    @Test fun `unbalanced individual operation is detected before source reconciliation`() {
        val s=seed();s.transfer();s.corrupt("DELETE FROM balance_entries WHERE user_id=2")
        s.rejects("Несбалансированная операция")
    }
    @Test fun `historical amounts accumulate without overflowing SQLite or Long`() {
        val s=seed();s.transfer()
        s.database.write { c ->
            val id=sqlQuery(c,"SELECT id FROM actions WHERE transfer_id='p'") { it.getLong(1) }.single()
            sqlUpdate(c,"DELETE FROM balance_entries")
            val amounts=listOf(Long.MAX_VALUE,300L,-Long.MAX_VALUE,-300L,Long.MAX_VALUE,-Long.MAX_VALUE)
            val users=listOf(1L,1L,1L,2L,2L,2L)
            amounts.forEachIndexed { index,amount -> sqlUpdate(c,"INSERT INTO balance_entries VALUES(?,-1,?,?,?)",id,index,users[index],amount) }
        }
        assertContains(s.database.verify(),"целостность в порядке")
    }
    @Test fun `cancelled training must not retain accounted entries`() {
        val s=seed();s.training();s.corrupt("UPDATE trainings SET status='CANCELLED',applied_version=0")
        s.rejects("ожидается 0 ₽")
    }
}
