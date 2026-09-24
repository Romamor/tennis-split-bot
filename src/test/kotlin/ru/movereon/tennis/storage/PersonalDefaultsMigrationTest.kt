package ru.movereon.tennis.storage

import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.selfservice.*
import kotlin.test.*

class PersonalDefaultsMigrationTest {
    @TempDir lateinit var dir:Path

    @Test fun `schema six adds personal defaults removes group default and preserves old buttons and money`() {
        val file=dir.resolve("v5.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { stmt ->
            val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema-v5.sql")).bufferedReader().readText()
            ddl.split(';').filter { it.isNotBlank() }.forEach(stmt::execute)
            stmt.execute("PRAGMA application_id=${Database.APPLICATION_ID}");stmt.execute("PRAGMA user_version=5")
            stmt.execute("INSERT INTO users(id,first_name) VALUES(1,'Игрок'),(2,'Второй')")
            stmt.execute("INSERT INTO groups VALUES(-1,'Группа','Europe/Moscow','20:00')")
            stmt.execute("INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1),(-1,2,1)")
            stmt.execute("INSERT INTO actions(group_id,request_id,actor_id,kind,payload_json,after_json,result_version,occurred_at,needs_delivery) VALUES(-1,'old',1,'fixture','{}','{}',1,'2026-09-13T10:00:00Z',0)")
            stmt.execute("INSERT INTO balance_entries VALUES(1,-1,0,1,150),(1,-1,1,2,-150)")
            stmt.execute("""INSERT INTO bot_sessions(user_id,chat_id,group_id,input_json) VALUES(1,1,-1,'{"kind":"ready","group":-1,"title":"Черновик","date":"2026-09-13","time":"19:30"}')""")
            stmt.execute("""INSERT INTO bot_events(update_id,user_id,group_id,plan_json,completed) VALUES(42,1,-1,'{"user":1,"chat":1,"screen":{"kind":"form","group":-1},"form":{"kind":"ready","group":-1,"title":"Сохранённое событие","date":"2026-09-13","time":"20:00"}}',0)""")
            stmt.execute("INSERT INTO bot_buttons(token,group_id,owner_id,scope,permanent,payload_hash,action_json,active,expires_at) VALUES('old',-1,1,'old',0,'hash','{\"kind\":\"training\",\"group\":-1}',1,0)")
        } }
        val db=Database(file);val service=SettlementService(db)
        assertTrue(db.verify().contains("Схема 9"))
        assertEquals(TrainingDefaults(),service.trainingDefaults(1))
        assertEquals(mapOf(1L to 150L,2L to -150L),service.balances(Access(-1,1)))
        db.read { c ->
            assertFalse(sqlQuery(c,"PRAGMA table_info(groups)") { it.getString("name") }.contains("default_start_time"))
            assertEquals(-1,sqlQuery(c,"SELECT group_id FROM bot_buttons WHERE token='old'") { it.getInt(1) }.single())
        }
        val store=InteractionStore(db)
        assertEquals("group",store.form(1,1)!!.kind);assertEquals(0,store.form(1,1)!!.group)
        assertEquals("Черновик",store.form(1,1)!!.title);assertEquals("19:30",store.form(1,1)!!.time)
        assertEquals(0,store.plan(42)!!.screen.group);assertEquals("group",store.plan(42)!!.form!!.kind)
        assertEquals("20:00",store.plan(42)!!.form!!.time)
        val global=store.button(ScreenAction("settings",0),1,"private")
        assertEquals(0,store.button(global)!!.action.group)
        service.updateTrainingDefaults(1,DefaultTrainingUpdate("title","Теннис","Спарринг"))
        assertEquals("Спарринг",SettlementService(Database(file)).trainingDefaults(1).title)
        assertEquals(1,service.history(Access(-1,1)).total)
    }
}
