package ru.movereon.tennis.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.*

class DatabaseMaintenanceTest {
    @TempDir lateinit var dir:Path
    private fun seed(path:Path):SettlementService {
        val s=SettlementService(Database(path));s.register(SettlementGroup(-1,"Test","Europe/Moscow"))
        for(id in 1L..2L) { s.remember(Account(id,"User $id"));s.rememberMembership(-1,id,true) }
        s.execute(Access(-1,1),"transfer",SettlementCommand.RecordTransfer("transfer",1,2,300,"2026-09-10"))
        return s
    }
    @Test fun `CLI backup and verify preserve the old source schema and complete financial data`() {
        val source=dir.resolve("source space.sqlite");val backup=dir.resolve("copy.sqlite")
        val s=seed(source);val balances=s.balances(Access(-1,1))
        s.database.write { c ->
            sqlUpdate(c,"ALTER TABLE group_users DROP COLUMN attendance_count")
            sqlUpdate(c,"ALTER TABLE group_users DROP COLUMN has_played")
            c.createStatement().use { it.execute("PRAGMA user_version=1") }
        }
        val before=Files.readAllBytes(source)
        ru.movereon.tennis.main(arrayOf("verify",source.toString()))
        ru.movereon.tennis.main(arrayOf("backup",source.toString(),backup.toString()))
        assertContentEquals(before,Files.readAllBytes(source))
        val copy=Database(backup,readOnly=true)
        assertEquals(1,copy.read { sqlQuery(it,"PRAGMA user_version") { r -> r.getInt(1) }.single() })
        assertEquals(balances,copy.balances(-1))
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(backup))
        assertFailsWith<IllegalArgumentException> { copy.backup(backup) }
        assertFailsWith<IllegalStateException> { copy.write { sqlUpdate(it,"DELETE FROM actions") } }
    }
    @Test fun `live backup includes committed WAL pages and can be restored independently`() {
        val source=dir.resolve("wal.sqlite");val s=seed(source)
        DriverManager.getConnection("jdbc:sqlite:$source").use { keeper ->
            keeper.createStatement().use { it.execute("PRAGMA journal_mode=WAL");it.execute("PRAGMA wal_autocheckpoint=0");it.execute("BEGIN");it.executeQuery("SELECT COUNT(*) FROM actions").use { r -> r.next() } }
            s.execute(Access(-1,2),"new",SettlementCommand.RecordTransfer("second",2,1,125,"2026-09-11"))
            assertTrue(Files.size(Path.of("$source-wal"))>0)
            val backup=Database(source,readOnly=true).backup(dir.resolve("wal-copy.sqlite"))
            val restored=SettlementService(Database(backup))
            assertEquals(s.balances(Access(-1,1)),restored.balances(Access(-1,1)))
            assertEquals(s.history(Access(-1,1)).items,restored.history(Access(-1,1)).items)
            assertTrue(restored.database.verify().contains("целостность в порядке"))
        }
    }
}
