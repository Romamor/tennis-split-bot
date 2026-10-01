package ru.movereon.tennis.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class ConnectionReuseTest {
    @TempDir lateinit var dir:Path
    @Test fun `reused connections preserve nested read isolation rollback and scoped closing`() {
        val db=Database(dir.resolve("source.sqlite"))
        db.write { c -> sqlUpdate(c,"CREATE TABLE probe(value INTEGER NOT NULL)");sqlUpdate(c,"INSERT INTO probe VALUES(1)") }
        val before=db.openedConnections.get()
        db.withConnectionReuse {
            db.write { c ->
                sqlUpdate(c,"UPDATE probe SET value=2")
                assertEquals(1,db.read { r -> sqlQuery(r,"SELECT value FROM probe") { it.getInt(1) }.single() })
            }
            assertEquals(2,db.openedConnections.get()-before)
            repeat(10) { assertEquals(2,db.read { c -> sqlQuery(c,"SELECT value FROM probe") { it.getInt(1) }.single() }) }
            assertEquals(2,db.openedConnections.get()-before)
            assertFailsWith<IllegalStateException> { db.write { c -> sqlUpdate(c,"UPDATE probe SET value=99");error("rollback") } }
            assertEquals(2,db.read { c -> sqlQuery(c,"SELECT value FROM probe") { it.getInt(1) }.single() })
            assertTrue(Files.exists(Path.of(db.path.toString()+"-wal")))
        }
        assertFalse(Files.exists(Path.of(db.path.toString()+"-wal")))
        val closed=db.openedConnections.get()
        db.read { c -> sqlQuery(c,"SELECT value FROM probe") { it.getInt(1) } }
        assertEquals(closed+1,db.openedConnections.get())
    }
    @Test fun `backup includes committed WAL and reuse scope closes after failure`() {
        val db=Database(dir.resolve("wal.sqlite"));val backup=dir.resolve("copy.sqlite")
        assertFailsWith<IllegalStateException> {
            db.withConnectionReuse {
                db.write { c -> sqlUpdate(c,"INSERT INTO users(id,first_name) VALUES(1,'Synthetic')") }
                db.backup(backup)
                error("end scope")
            }
        }
        val restored=Database(backup,readOnly=true)
        assertEquals("Synthetic",restored.read { c -> sqlQuery(c,"SELECT first_name FROM users WHERE id=1") { it.getString(1) }.single() })
        restored.verify();db.verify()
        db.withConnectionReuse { db.read { c -> assertEquals(1,sqlQuery(c,"SELECT COUNT(*) FROM users") { it.getInt(1) }.single()) } }
    }
}
