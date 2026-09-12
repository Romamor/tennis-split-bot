package ru.movereon.tennis.storage

import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.selfservice.*
import kotlin.test.*

class TrainingStateMigrationTest {
    @TempDir lateinit var dir:Path

    @Test fun `legacy review reverses only its training preserves history and migrates once`() {
        val source=Database(dir.resolve("seed.sqlite"));val s=SettlementService(source)
        for(user in 1L..2L) s.remember(Account(user,"Игрок $user"))
        for(group in listOf(-1L,-2L)) {
            s.register(SettlementGroup(group,"Группа","Europe/Moscow"))
            for(user in 1L..2L) s.rememberMembership(group,user,true)
            val a=Access(group,1,true)
            s.execute(a,"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-12","18:30"))
            s.execute(a,"add",SettlementCommand.AddPlayers("t",1,listOf(1,2)))
            for(user in 1L..2L) s.execute(a,"time$user",SettlementCommand.ChangeAttendance("t",user,AttendanceChange.SET_MINUTES,60))
            s.execute(a,"paid",SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300))
            s.execute(a,"finish",SettlementCommand.FinishTraining("t",s.training(a,"t").version))
            s.execute(a,"transfer",SettlementCommand.RecordTransfer("p",1,2,50,"2026-09-12"))
        }
        val file=dir.resolve("v4.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { stmt ->
                val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema-v4.sql")).bufferedReader().readText()
                ddl.split(';').filter { it.isNotBlank() }.forEach(stmt::execute)
                c.prepareStatement("ATTACH DATABASE ? AS seed").use { it.setString(1,source.path.toString());it.execute() }
                val tables=sqlQuery(c,"SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'") { it.getString(1) }
                for(table in tables) {
                    val cols=sqlQuery(c,"PRAGMA table_info($table)") { it.getString("name") }.joinToString(",")
                    stmt.execute("INSERT INTO $table($cols) SELECT $cols FROM seed.$table")
                }
                stmt.execute("PRAGMA application_id=${Database.APPLICATION_ID}");stmt.execute("PRAGMA user_version=4")
                stmt.execute("UPDATE trainings SET status='REVIEW' WHERE group_id=-1")
                stmt.execute("UPDATE actions SET delivered_at='2026-09-12T10:00:00Z'")
                stmt.execute("INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,message_id,status) VALUES('training:-1:t',-1,-1,10,'SENT'),('training:-2:t',-2,-2,20,'SENT')")
            }
            val t=s.training(Access(-1,1,true),"t");val json=Json { encodeDefaults=true }
            val oldJson=JsonObject(json.encodeToJsonElement(t).jsonObject+("phase" to JsonPrimitive("REVIEW"))).toString()
            sqlUpdate(c,"UPDATE actions SET after_json=? WHERE group_id=-1 AND kind='ChangeAttendance'",oldJson)
        }
        val old=Database(file,readOnly=true)
        val oldActions=old.read { sqlQuery(it,"SELECT id,after_json FROM actions ORDER BY id") { r->r.getLong(1) to r.getString(2) } }
        val oldEntries=old.read { sqlQuery(it,"SELECT action_id,entry_index,amount FROM balance_entries ORDER BY action_id,entry_index") { r->Triple(r.getLong(1),r.getInt(2),r.getLong(3)) } }
        val oldSecond=s.balances(Access(-2,1,true))
        val migrated=Database(file);val current=SettlementService(migrated);val a=Access(-1,1,true)
        assertTrue(migrated.verify().contains("Схема 5"))
        assertEquals(TrainingPhase.OPEN,current.training(a,"t").phase)
        assertEquals(0,current.training(a,"t").appliedVersion)
        assertTrue(current.training(a,"t").players.none { it.appliedPlaying })
        assertEquals(mapOf(1L to 50L,2L to -50L),current.balances(a))
        assertEquals(oldSecond,current.balances(Access(-2,1,true)))
        migrated.read { c ->
            assertEquals(oldActions,sqlQuery(c,"SELECT id,after_json FROM actions WHERE kind<>'MigrateTrainingState' ORDER BY id") { it.getLong(1) to it.getString(2) })
            assertEquals(oldEntries,sqlQuery(c,"SELECT action_id,entry_index,amount FROM balance_entries WHERE action_id<=? ORDER BY action_id,entry_index",oldActions.last().first) { Triple(it.getLong(1),it.getInt(2),it.getLong(3)) })
        }
        assertFails { migrated.write { sqlUpdate(it,"UPDATE trainings SET status='REVIEW' WHERE group_id=-1") } }
        val store=InteractionStore(migrated)
        assertEquals(setOf(-1L,-2L),store.pendingCards().map { it.first }.toSet())
        val history=Screens(current,store,"demo_bot").render(ScreenAction("history",-1,"t"),a,"history",1)
        assertTrue(history.text.contains("Обновление бота"))
        assertFalse(history.text.contains("REVIEW"))
        val count=current.history(a).total
        migrated.write { sqlUpdate(it,"UPDATE actions SET delivered_at='2026-09-12T11:00:00Z'") }
        assertEquals(count,SettlementService(Database(file)).history(a).total)
        assertTrue(InteractionStore(Database(file)).pendingCards().isEmpty())
        val reopened=current.training(a,"t")
        current.execute(a,"new-finish",SettlementCommand.FinishTraining("t",reopened.version))
        assertEquals(mapOf(1L to 200L,2L to -200L),current.balances(a))
    }
}
