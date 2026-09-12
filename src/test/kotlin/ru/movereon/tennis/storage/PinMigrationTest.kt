package ru.movereon.tennis.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class PinMigrationTest {
    @TempDir lateinit var dir:Path
    @Test fun `schema three delivery states survive schema four and group defaults are added`() {
        val file=dir.resolve("old.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { s ->
            val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema-v3.sql")).bufferedReader().readText()
            ddl.split(';').filter { it.isNotBlank() }.forEach(s::execute)
            s.execute("PRAGMA application_id=${Database.APPLICATION_ID}");s.execute("PRAGMA user_version=3")
            s.execute("INSERT INTO users(id,first_name) VALUES(1,'Игрок')")
            s.execute("INSERT INTO groups VALUES(-1,'Тест','Europe/Moscow')")
            s.execute("INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1)")
            s.execute("INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,user_id,message_id,status,pin_status) VALUES('old',-1,-1,1,42,'SENT','UNKNOWN')")
        } }
        assertTrue(Database(file,readOnly=true).verify().contains("Схема 3"))
        val db=Database(file)
        assertTrue(db.verify().contains("Схема 5"))
        db.read { c ->
            assertEquals("UNKNOWN",sqlQuery(c,"SELECT pin_status FROM bot_deliveries") { it.getString(1) }.single())
            assertEquals(42,sqlQuery(c,"SELECT message_id FROM bot_deliveries") { it.getInt(1) }.single())
            assertEquals("18:30",sqlQuery(c,"SELECT default_start_time FROM groups") { it.getString(1) }.single())
        }
        db.write { sqlUpdate(it,"UPDATE bot_deliveries SET pin_status='UNPIN_PENDING'") }
        assertTrue(Database(file).verify().contains("Схема 5"))
    }
}
