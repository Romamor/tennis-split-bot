package ru.movereon.tennis.application

import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowMigrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `schema one financial history migrates without recalculation and existing IDs can be linked`() {
        val path = directory.resolve("legacy.sqlite")
        val a = ParticipantId("a")
        val b = ParticipantId("b")
        val actor = Actor("telegram:1", a)
        val input = Command.RecordTransfer("advance", a, b, 100)
        val book = AccountBook("legacy")
        val receipt = book.execute("original", actor, input)
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            val schema = requireNotNull(javaClass.getResourceAsStream("/db/001_accounting.sql")).bufferedReader().use { it.readText() }
            connection.createStatement().use { statement ->
                schema.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                statement.execute("PRAGMA application_id=1414745683")
                statement.execute("PRAGMA user_version=1")
            }
            connection.prepareStatement("INSERT INTO group_state VALUES(?,?,?)").use { statement ->
                statement.setString(1, "legacy"); statement.setLong(2, 1); statement.setString(3, StorageCodec.encodeCheckpoint(book.checkpoint()))
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                listOf("legacy", 1, "original", actor.id, "a", "advance", "transfer_recorded", 1,
                    StorageCodec.encodeCommand(input), StorageCodec.encodeDetails(OperationDetails()), "2026-09-07T12:00:00Z")
                    .forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO balance_entries VALUES('legacy',1,0,'a',100),('legacy',1,1,'b',-100)")
                statement.execute("INSERT INTO outbox VALUES('legacy',1,NULL)")
            }
        }
        val store = SqliteAccountingStore(path)
        assertEquals(IntegrityReport(1, 1), store.verifyIntegrity())
        assertEquals(receipt, store.execute("legacy", "original", actor, input))
        val service = GroupService(store)
        val member = VerifiedGroupMember("legacy", 1, true)
        service.execute(member, "initialize", WorkflowCommand.CreateGroup("Europe/Moscow"))
        assertEquals(mapOf("a" to 100L, "b" to -100L), service.participants(member).associate { it.participant.id to it.balance })
        service.execute(member, "claim", WorkflowCommand.LinkSelf("a", 1))
        service.execute(member, "rename", WorkflowCommand.RenameParticipant("a", 2, "Андрей"))
        assertEquals(1, store.history("legacy").size)
        assertEquals(mapOf(a to 100L, b to -100L), store.balances("legacy"))
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows -> rows.next(); assertEquals(4, rows.getInt(1)) }
        } }
    }
}
