package ru.movereon.tennis.selfservice

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import kotlin.test.assertTrue

/** Synthetic storage workload, never connects to Telegram or a real database. */
@Tag("storage-simulation")
class StorageSimulationTest {
    @Serializable data class Measurement(val players:Int,val members:Int,val trainings:Int,val bytes:Long,val events:Long,
        val actions:Long,val eventPlanBytes:Long,val historyBytes:Long,val buttonBytes:Long,val freePages:Long,
        val balances:Map<Long,Long>,val tableBytes:Map<String,Long>)
    private val outputJson=Json { prettyPrint=true }
    @Test fun `measure repeated training workflows`() {
        val root=Path.of(System.getProperty("storage.dir"));Files.createDirectories(root)
        val count=System.getProperty("storage.trainings").toInt()
        val members=System.getProperty("storage.members").toInt()
        val results=System.getProperty("storage.players").split(",").map(String::toInt).map { players ->
            require(players in 5..members)
            val path=root.resolve("players-$players.sqlite")
            require(!Files.exists(path)) { "Для измерения нужен новый каталог" }
            var instant=Instant.parse("2026-01-01T16:00:00Z")
            val clock=object:Clock() {
                override fun getZone()=ZoneOffset.UTC
                override fun withZone(zone:ZoneId):Clock=Clock.fixed(instant,zone)
                override fun instant()=instant
            }
            val db=Database(path);val service=SettlementService(db,clock);val state=InteractionStore(db,clock)
            val screens=Screens(service,state,"example_bot");val admin=Access(-1,1,true)
            service.register(SettlementGroup(-1,"Группа из $members человек","Europe/Moscow"))
            for(id in 1L..members.toLong()) { service.remember(Account(id,"Игрок $id"));service.rememberMembership(-1,id,true) }
            var update=0L
            fun render(action:ScreenAction,user:Long?,form:InputForm?=null) {
                val public=action.kind=="public"
                val scope=if(public) "training:-1:${action.id}" else "personal:$user:$user"
                val out=screens.render(action,if(public) admin else Access(-1,requireNotNull(user)),scope,user,form,inGroup=public)
                state.protect(out.tokens);state.replace(scope,out.tokens)
                if(public) { state.sending(scope,-1,-1,null);state.deliveryResult(scope,"SENT",update) }
            }
            fun command(command:SettlementCommand,actor:Long=1,training:String="") {
                val event=++update
                state.plan(event,EventPlan(actor,actor,ScreenAction("training",-1,training),command))
                service.execute(Access(-1,actor,actor==1L),"telegram:$event",command)
                if(training.isNotEmpty()) render(ScreenAction("public",-1,training),null)
                state.complete(event);instant=instant.plusSeconds(3)
            }
            repeat(count) { trainingIndex ->
                val id="training-$trainingIndex";val date=instant.atZone(ZoneOffset.UTC).toLocalDate().toString()
                command(SettlementCommand.CreateTraining(id,"Теннис",date,"19:00"),training=id)
                val trainingUsers=listOf(1L,2L)+(0 until players-2).map { 3L+(trainingIndex*(players-2)+it)%(members-2) }
                for(user in trainingUsers) {
                    var draft=AttendanceDraft(id,user,null,Attendance(user,true))
                    repeat(4) { step ->
                        draft=draft.copy(value=draft.value.copy(minutes=if(step==1) 90 else 60,
                            paid=if(step>=2) when(user) { 1L->350L;2L->400L;else->0L } else 0))
                        val event=++update
                        state.plan(event,EventPlan(user,user,ScreenAction("player",-1,id,user=user),draft=draft))
                        state.attendanceDraft(user,-1,draft)
                        render(ScreenAction("player",-1,id,user=user),user)
                        state.complete(event);instant=instant.plusSeconds(3)
                    }
                    command(SettlementCommand.SaveAttendance(id,user,null,draft.value),user,id)
                    state.attendanceDraft(user,-1,null)
                }
                command(SettlementCommand.FinishTraining(id,service.training(admin,id).version),training=id)
                command(SettlementCommand.ReopenTraining(id,service.training(admin,id).version),training=id)
                val old=service.training(admin,id).players.last()
                command(SettlementCommand.SaveAttendance(id,old.userId,old,old.copy(minutes=90)),training=id)
                command(SettlementCommand.FinishTraining(id,service.training(admin,id).version),training=id)
                repeat(3) { transfer ->
                    val from=trainingUsers.drop(2)[transfer]
                    command(SettlementCommand.RecordTransfer("$id-transfer-$transfer",from,1,50,date),actor=from)
                }
                state.cardDelivered(-1,id,Long.MAX_VALUE)
                val next=trainingIndex+1
                instant=Instant.parse("2026-01-01T16:00:00Z").plusSeconds((next/3*7+listOf(0,2,4)[next%3])*86400L);state.cleanup()
            }
            assertTrue(db.verify().contains("целостность в порядке"))
            db.read { c ->
                fun scalar(sql:String)=sqlQuery(c,sql) { it.getLong(1) }.single()
                val tables=runCatching { sqlQuery(c,"SELECT name,SUM(pgsize) FROM dbstat GROUP BY name") { it.getString(1) to it.getLong(2) }.toMap() }.getOrDefault(emptyMap())
                Measurement(players,members,count,Files.size(path),scalar("SELECT COUNT(*) FROM bot_events"),scalar("SELECT COUNT(*) FROM actions"),
                    scalar("SELECT COALESCE(SUM(LENGTH(CAST(plan_json AS BLOB))),0) FROM bot_events"),
                    scalar("SELECT COALESCE(SUM(LENGTH(CAST(before_json AS BLOB))),0)+COALESCE(SUM(LENGTH(CAST(after_json AS BLOB))),0) FROM actions"),
                    scalar("SELECT COALESCE(SUM(LENGTH(CAST(action_json AS BLOB))),0) FROM bot_buttons"),scalar("PRAGMA freelist_count"),
                    service.balances(admin),tables)
            }
        }
        Files.writeString(root.resolve("measurements.json"),outputJson.encodeToString(results))
        results.forEach { println("players=${it.players}, trainings=${it.trainings}, bytes=${it.bytes}") }
    }
}
