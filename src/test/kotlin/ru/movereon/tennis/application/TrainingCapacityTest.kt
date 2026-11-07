package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.core.ErrorCode
import ru.movereon.tennis.storage.*
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class TrainingCapacityTest {
    @TempDir lateinit var dir:Path
    private val admin=Access(-1,1,true)
    private lateinit var service:SettlementService
    private var sequence=0
    private fun setup(trackTime:Boolean=true) {
        service=SettlementService(Database(dir.resolve("capacity.sqlite")))
        service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        service.database.write { c ->
            (1L..55L).forEach {
                sqlUpdate(c,"INSERT INTO users(id,first_name) VALUES(?,?)",it,"Игрок $it")
                sqlUpdate(c,"INSERT INTO group_users(group_id,user_id,present) VALUES(-1,?,1)",it)
            }
        }
        if(!trackTime) service.setGroupTrainingRule(admin,"time",false)
        execute(SettlementCommand.CreateTraining("t","Теннис","2026-11-06","18:30"))
    }
    private fun execute(command:SettlementCommand)=service.execute(admin,"request:${sequence++}",command)
    private fun training()=service.training(admin,"t")
    private fun add(users:List<Long>)=execute(SettlementCommand.AddPlayers("t",training().version,users))
    private fun change(user:Long,kind:AttendanceChange,value:Long=0)=execute(SettlementCommand.ChangeAttendance("t",user,kind,value))
    private fun rejected(block:()->Unit) {
        val before=training();val history=service.history(admin).total;val balances=service.balances(admin)
        val error=assertFailsWith<AccountingException>(block=block)
        assertEquals(ErrorCode.INVALID_INPUT,error.code);assertContains(error.message.orEmpty(),"50 участников, включая гостей")
        assertEquals(before,training());assertEquals(history,service.history(admin).total);assertEquals(balances,service.balances(admin))
    }

    @Test fun `zero minute signups accept fifty and reject fifty first atomically`() {
        setup();add((1L..49L).toList());change(50,AttendanceChange.JOIN)
        assertEquals(50,training().participantCount);assertTrue(training().players.all { it.minutes==0L })
        rejected { change(51,AttendanceChange.JOIN) }
        change(50,AttendanceChange.LEAVE);change(51,AttendanceChange.JOIN)
        assertEquals(50,training().participantCount)
    }

    @Test fun `bulk add counts existing guests and rolls back the whole batch`() {
        setup();add((1L..48L).toList());change(1,AttendanceChange.GUEST,1)
        rejected { add(listOf(49,50)) }
        add(listOf(1,49));assertEquals(50,training().participantCount)
        rejected { add(listOf(50,51,52)) }
    }

    @Test fun `concurrent signups cannot claim the last place twice`() {
        setup();add((1L..49L).toList())
        val workers=Executors.newFixedThreadPool(2)
        try {
            val attempts=listOf(50L,51L).map { user -> workers.submit<Boolean> {
                runCatching { service.execute(Access(-1,user),"join:$user",SettlementCommand.ChangeAttendance("t",user,AttendanceChange.JOIN)) }.isSuccess
            } }
            assertEquals(1,attempts.count { it.get() });assertEquals(50,training().participantCount)
        } finally { workers.shutdownNow() }
    }

    @Test fun `guest buttons and saved attendance share the total limit`() {
        setup();add(listOf(1));change(1,AttendanceChange.GUEST,49)
        assertEquals(50,training().participantCount)
        rejected { change(1,AttendanceChange.ADJUST_GUESTS,1) }
        rejected { change(1,AttendanceChange.GUEST,50) }
        val before=training().players.single()
        rejected { execute(SettlementCommand.SaveAttendance("t",1,before,before.copy(guestCount=50))) }
        rejected { execute(SettlementCommand.SaveAttendance("t",2,null,Attendance(2,true))) }
        change(1,AttendanceChange.ADJUST_GUESTS,-1);change(2,AttendanceChange.JOIN)
        assertEquals(50,training().participantCount)
    }

    @Test fun `legacy guest duration button cannot exceed the total limit`() {
        setup();add((1L..50L).toList());change(1,AttendanceChange.SET_MINUTES,60)
        rejected { change(1,AttendanceChange.SET_GUEST_MINUTES,60) }
    }

    @Test fun `equal time and guest rule switches keep guests within the same capacity`() {
        setup(trackTime=false);add((1L..49L).toList());change(1,AttendanceChange.GUEST,1)
        val players=training().players
        service.setGroupTrainingRule(admin,"guests",false)
        assertEquals(players,training().players);assertEquals(50,training().participantCount)
        rejected { change(50,AttendanceChange.JOIN) }
        service.setGroupTrainingRule(admin,"guests",true);service.setGroupTrainingRule(admin,"time",true)
        assertEquals(players,training().players)
        rejected { change(1,AttendanceChange.ADJUST_GUESTS,1) }
    }

    @Test fun `existing oversized records keep their data and allow reductions or cancellation`() {
        setup();add(listOf(1))
        service.database.write { c -> sqlUpdate(c,"UPDATE training_players SET guest_count=52 WHERE group_id=-1 AND training_id='t'") }
        assertEquals(53,training().participantCount)
        change(1,AttendanceChange.SET_MINUTES,60)
        service.setGroupTrainingRule(admin,"guests",false);service.setGroupTrainingRule(admin,"guests",true)
        assertEquals(53,training().participantCount)
        change(1,AttendanceChange.ADJUST_GUESTS,-1);assertEquals(52,training().participantCount)
        val row=training().players.single()
        execute(SettlementCommand.SaveAttendance("t",1,row,row.copy(guestCount=50)))
        assertEquals(51,training().participantCount)
        rejected { change(1,AttendanceChange.ADJUST_GUESTS,1) }
        rejected { execute(SettlementCommand.FinishTraining("t",training().version)) }
        val saved=training().players
        execute(SettlementCommand.CancelTraining("t",training().version))
        assertEquals(saved,training().players);assertEquals(TrainingPhase.CANCELLED,training().phase)
        execute(SettlementCommand.RestoreTraining("t",training().version))
        assertEquals(saved,training().players)
        change(1,AttendanceChange.ADJUST_GUESTS,-1);assertEquals(50,training().participantCount)
    }

    private fun poll():Pair<TrainingPolls,TrainingPoll> {
        val polls=TrainingPolls(service);polls.setEnabled(admin,true)
        val p=polls.create(admin,"poll","Сбор","2026-11-06","18:30","Не приду")
        polls.attach(p,"telegram-poll",1)
        return polls to polls.get(-1,p.id)
    }
    @Test fun `oversized open poll is rejected before stopping and votes can be withdrawn`() {
        setup();val (polls,p)=poll()
        (1L..51L).forEach { polls.vote(100+it,p,it,0) }
        assertFailsWith<AccountingException> { polls.beginClose(admin,p.id) }
        assertEquals("OPEN",polls.get(-1,p.id).status);assertEquals(51,polls.count(p))
        polls.vote(200,p,51,null);polls.beginClose(admin,p.id);polls.stopped(p)
        assertTrue(polls.finish(p));assertTrue(polls.finish(p))
        assertEquals(50,service.training(admin,p.id).participantCount)
        assertEquals(0,polls.count(p))
    }

    @Test fun `late fifty first vote pauses stopped conversion without partial training or lost votes`() {
        setup();val (polls,p)=poll()
        (1L..50L).forEach { polls.vote(100+it,p,it,0) }
        polls.beginClose(admin,p.id);polls.vote(200,p,51,0);polls.stopped(p)
        val before=service.history(admin).total
        assertFalse(polls.finish(p))
        val failed=polls.get(-1,p.id)
        assertEquals("FAILED",failed.status);assertTrue(failed.stopped);assertTrue(failed.awaitingCloseRecovery)
        assertEquals(51,polls.count(p));assertFalse(polls.hasClosingPolls());assertTrue(polls.closingPolls().isEmpty())
        assertEquals(before,service.history(admin).total)
        assertFailsWith<AccountingException> { service.training(admin,p.id) }
        assertTrue(service.balances(admin).isEmpty())
    }
}
