package ru.movereon.tennis.storage

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DeliveryRetryMigrationTest {
    @TempDir lateinit var dir:Path

    private fun snapshot(c:Connection):Map<String,List<List<String?>>> =
        sqlQuery(c,"SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.getString(1) }
            .associateWith { table ->
                val columns=sqlQuery(c,"PRAGMA table_info($table)") { it.getString("name") }
                    .filterNot { table=="bot_deliveries" && it in setOf("retry_at","pin_retry_at") || table=="bot_polling" && it=="retry_at" }
                sqlQuery(c,"SELECT ${columns.joinToString(",")} FROM $table ORDER BY rowid") { r -> columns.indices.map { r.getString(it+1) } }
            }

    @Test fun `version eleven preserves every stored row and persists delivery and polling retry deadlines across reopen`() {
        val file=dir.resolve("v10.sqlite")
        val before=DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { s ->
                val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema-v10.sql")).bufferedReader().use { it.readText() }
                ddl.split(';').filter { it.isNotBlank() }.forEach(s::execute)
                s.execute("PRAGMA application_id=${Database.APPLICATION_ID}");s.execute("PRAGMA user_version=10")
                s.execute("INSERT INTO users(id,first_name) VALUES(1,'Synthetic participant'),(2,'Second participant')")
                s.execute("INSERT INTO groups(id,title,time_zone) VALUES(-1,'Synthetic group','Europe/Moscow')")
                s.execute("INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1),(-1,2,1)")
                s.execute("INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,status,version,created_by,created_at) VALUES(-1,'old',1,2,150,'2026-11-06','ACTIVE',1,1,'2026-11-06T10:00:00Z')")
                s.execute("INSERT INTO actions(group_id,request_id,actor_id,kind,transfer_id,payload_json,after_json,result_version,occurred_at,needs_delivery) VALUES(-1,'old',1,'RecordAdminPayment','old','{}','{}',1,'2026-11-06T10:00:00Z',1)")
                s.execute("INSERT INTO balance_entries VALUES(1,-1,0,1,150),(1,-1,1,2,-150)")
                s.execute("INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,user_id,message_id,status,pin_status) VALUES('old',-1,-1,1,42,'RETRY','UNPIN_PENDING'),('uncertain',-1,1,1,NULL,'UNKNOWN','NONE')")
                s.execute("INSERT INTO bot_events(update_id,user_id,group_id,plan_json,update_json,ordering_key,received_order,next_attempt_at) VALUES(10,1,-1,'{}','{}','group:-1',5,1234)")
                s.execute("UPDATE bot_polling SET next_update_id=11,received_order=5")
                s.execute("INSERT INTO bot_sessions(user_id,chat_id,group_id,input_json,panel_json) VALUES(1,1,-1,'{}','{}')")
                s.execute("INSERT INTO bot_buttons(token,group_id,owner_id,scope,permanent,payload_hash,action_json,active,expires_at) VALUES('old',-1,1,'old',0,'hash','{}',1,1234)")
            }
            snapshot(c)
        }
        repeat(2) {
            val db=Database(file)
            assertContains(db.verify(),"Схема 11")
            db.read { c ->
                assertEquals(before,snapshot(c))
                assertEquals(listOf(0L to 0L,0L to 0L),sqlQuery(c,"SELECT retry_at,pin_retry_at FROM bot_deliveries ORDER BY delivery_key") { it.getLong(1) to it.getLong(2) })
                assertEquals(Triple(11L,5L,0L),sqlQuery(c,"SELECT next_update_id,received_order,retry_at FROM bot_polling WHERE singleton=1") { Triple(it.getLong(1),it.getLong(2),it.getLong(3)) }.single())
                assertTrue(sqlQuery(c,"PRAGMA foreign_key_check") { it.getString(1) }.isEmpty())
                assertTrue(sqlQuery(c,"SELECT name FROM sqlite_schema WHERE name='action_history'") { it.getString(1) }.isEmpty())
                val uniqueIndexes=sqlQuery(c,"PRAGMA index_list(actions)") { it.getString("name") to it.getBoolean("unique") }.filter { it.second }
                assertTrue(uniqueIndexes.any { (name,_) -> sqlQuery(c,"PRAGMA index_info($name)") { it.getString("name") }==listOf("group_id","id") })
            }
        }
        Database(file).write { c ->
            sqlUpdate(c,"UPDATE bot_deliveries SET retry_at=123456789,pin_retry_at=987654321 WHERE delivery_key='old'")
            sqlUpdate(c,"UPDATE bot_polling SET retry_at=456789123 WHERE singleton=1")
        }
        for(readOnly in listOf(false,true)) Database(file,readOnly=readOnly).read { c ->
            assertEquals(before,snapshot(c))
            assertEquals(123456789L to 987654321L,sqlQuery(c,"SELECT retry_at,pin_retry_at FROM bot_deliveries WHERE delivery_key='old'") { it.getLong(1) to it.getLong(2) }.single())
            assertEquals(Triple(11L,5L,456789123L),sqlQuery(c,"SELECT next_update_id,received_order,retry_at FROM bot_polling WHERE singleton=1") { Triple(it.getLong(1),it.getLong(2),it.getLong(3)) }.single())
        }
    }
}
