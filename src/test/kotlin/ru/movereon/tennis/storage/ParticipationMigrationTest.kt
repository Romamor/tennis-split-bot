package ru.movereon.tennis.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class ParticipationMigrationTest {
    @TempDir lateinit var directory:Path
    private val json=Json { encodeDefaults=true }

    @Test fun `version two migration preserves existing time history and ledger while enabling new participation rules`() {
        val path=directory.resolve("old.sqlite")
        val old=TrainingRecord(-1,"old","Теннис","2026-09-09","19:00",TrainingPhase.CLOSED,1,1,1,
            listOf(Attendance(1,true,60,30,300,0,true),Attendance(2,true,60,0,0,1,true)))
        val history=json.encodeToString(old).replace(Regex(",\"guestCount\":\\d+"),"")
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            val schema=requireNotNull(javaClass.getResourceAsStream("/db/schema-v2.sql")).bufferedReader().use { it.readText() }
            c.createStatement().use { statement ->
                schema.split(';').filter { it.isNotBlank() }.forEach(statement::execute)
                statement.execute("PRAGMA application_id=${Database.APPLICATION_ID}")
                statement.execute("PRAGMA user_version=2")
            }
            sqlUpdate(c,"INSERT INTO users(id,first_name) VALUES(1,'Игрок 1'),(2,'Игрок 2')")
            sqlUpdate(c,"INSERT INTO groups(id,title,time_zone) VALUES(-1,'Тестовая группа','Europe/Moscow')")
            sqlUpdate(c,"INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1),(-1,2,1)")
            sqlUpdate(c,"""INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,applied_version,created_by,created_at)
                VALUES(-1,'old','Теннис','2026-09-09','19:00','CLOSED',1,1,1,'2026-09-09T16:00:00Z')""")
            sqlUpdate(c,"""INSERT INTO training_players(group_id,training_id,user_id,playing,applied_playing,minutes,guest_minutes,paid,ordinal)
                VALUES(-1,'old',1,1,1,60,30,300,0),(-1,'old',2,1,1,60,0,0,1)""")
            sqlUpdate(c,"""INSERT INTO actions(id,group_id,request_id,actor_id,kind,training_id,payload_json,after_json,result_version,occurred_at,needs_delivery)
                VALUES(1,-1,'old-finish',1,'FinishTraining','old','{}',?,1,'2026-09-09T16:00:00Z',1)""",history)
            sqlUpdate(c,"INSERT INTO balance_entries(action_id,group_id,entry_index,user_id,amount) VALUES(1,-1,0,1,120),(1,-1,1,2,-120)")
        }
        val service=SettlementService(Database(path));val admin=Access(-1,1,true)
        assertEquals(old,service.training(admin,"old"))
        assertEquals(history,service.history(admin).items.single().after)
        assertEquals(mapOf(1L to 120L,2L to -120L),service.balances(admin))
        assertEquals(3,service.database.read { sqlQuery(it,"PRAGMA user_version") { r -> r.getInt(1) }.single() })
        service.execute(admin,"reopen",SettlementCommand.ReopenTraining("old",1))
        val unchanged=service.training(admin,"old").players.first()
        assertEquals(0,service.execute(admin,"unchanged",SettlementCommand.SaveAttendance("old",1,unchanged,unchanged)).id)
        assertEquals(30,service.training(admin,"old").players.first().guestMinutes)
        service.execute(admin,"time",SettlementCommand.ChangeAttendance("old",1,AttendanceChange.SET_MINUTES,90))
        service.execute(admin,"guests",SettlementCommand.ChangeAttendance("old",1,AttendanceChange.GUEST,2))
        val changed=service.training(admin,"old").players.first()
        assertEquals(2,changed.guestCount);assertEquals(90,changed.guestMinutes)
        assertEquals(mapOf(1L to 120L,2L to -120L),service.balances(admin))
        service.execute(admin,"finish",SettlementCommand.FinishTraining("old",service.training(admin,"old").version))
        assertEquals(mapOf(1L to 54L,2L to -54L),service.balances(admin))
        assertEquals(history,service.history(admin).items.last().after)
        assertTrue(service.database.verify().contains("целостность в порядке"))
    }
}
