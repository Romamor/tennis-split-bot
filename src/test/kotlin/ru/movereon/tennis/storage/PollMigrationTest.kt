package ru.movereon.tennis.storage

import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import kotlin.test.*

class PollMigrationTest {
    @TempDir lateinit var dir:Path
    @Test fun `schema seven preserves accounting defaults and enables no polls automatically`() {
        val file=dir.resolve("v6.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { stmt ->
            val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema.sql")).bufferedReader().use { it.readText() }
                .substringBefore("CREATE TABLE training_polls")
                .replace(",\n    polls_enabled INTEGER NOT NULL DEFAULT 0 CHECK(polls_enabled IN (0,1))","")
            ddl.split(';').filter { it.isNotBlank() }.forEach(stmt::execute)
            stmt.execute("PRAGMA application_id=${Database.APPLICATION_ID}");stmt.execute("PRAGMA user_version=6")
            stmt.execute("INSERT INTO users(id,first_name,training_time) VALUES(1,'Игрок','19:30'),(2,'Второй','18:30')")
            stmt.execute("INSERT INTO groups VALUES(-1,'Группа','Europe/Moscow')")
            stmt.execute("INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1),(-1,2,1)")
            stmt.execute("INSERT INTO actions(group_id,request_id,actor_id,kind,payload_json,after_json,result_version,occurred_at,needs_delivery) VALUES(-1,'old',1,'fixture','{}','{}',1,'2026-09-13T10:00:00Z',0)")
            stmt.execute("INSERT INTO balance_entries VALUES(1,-1,0,1,150),(1,-1,1,2,-150)")
        } }
        repeat(2) {
            val db=Database(file);val service=SettlementService(db)
            assertTrue(db.verify().contains("Схема 7"))
            assertFalse(TrainingPolls(service).enabled(-1))
            assertEquals("19:30",service.trainingDefaults(1).time)
            assertEquals(mapOf(1L to 150L,2L to -150L),service.balances(Access(-1,1)))
            assertEquals(1,service.history(Access(-1,1)).total)
            assertEquals(0,db.read { c -> sqlQuery(c,"SELECT COUNT(*) FROM training_polls") { it.getInt(1) }.single() })
        }
    }
}
