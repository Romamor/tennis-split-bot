package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.storage.Database
import java.nio.file.Path
import kotlin.test.assertEquals

class TrainingSummaryTest {
    @TempDir lateinit var dir:Path
    private lateinit var service:SettlementService
    private var sequence=0
    private fun setup() {
        service=SettlementService(Database(dir.resolve("summary.sqlite")))
        for(group in listOf(-1L,-2L)) {
            service.register(SettlementGroup(group,"Группа ${-group}","Europe/Moscow"))
            for(user in 1L..2L) {
                service.remember(Account(user,"Игрок $user"))
                service.rememberMembership(group,user,true)
            }
        }
    }
    private fun run(command:SettlementCommand,group:Long=-1,user:Long=1)=
        service.execute(Access(group,user,true),"request${sequence++}",command)
    private fun version(group:Long=-1,id:String="t")=service.training(Access(group,1),id).version
    private fun training(group:Long,paid1:Long,paid2:Long,minutes2:Long=60,guestMinutes:Long=0) {
        run(SettlementCommand.CreateTraining("t","Пример","2026-10-01","18:30"),group)
        run(SettlementCommand.AddPlayers("t",version(group),listOf(1,2)),group)
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_MINUTES,60),group)
        run(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_MINUTES,minutes2),group)
        if(guestMinutes>0) run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_GUEST_MINUTES,guestMinutes),group)
        if(paid1>0) run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,paid1),group)
        if(paid2>0) run(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_PAID,paid2),group)
        run(SettlementCommand.FinishTraining("t",version(group)),group)
    }
    private fun expect(cost:Long,remaining:Long) {
        val summary=service.myTrainings(1)
        assertEquals(cost.toBigInteger(),summary.participationCost)
        assertEquals(remaining.toBigInteger(),summary.remainingToPay)
    }

    @Test fun `cost includes guest shares and only received payments reduce remaining amount`() {
        setup();expect(0,0)
        training(-1,150,450,minutes2=120,guestMinutes=60)
        expect(300,150) // Player + guest have 120 minutes, equal to the other player's 120.
        assertEquals(60,service.myTrainings(1).minutes)
        run(SettlementCommand.SendOtherPayment("p",2,100))
        expect(300,150)
        run(SettlementCommand.ReceivePayment("p"),user=2)
        expect(300,50)
        run(SettlementCommand.ReceivePayment("p"),user=2)
        expect(300,50)
        run(SettlementCommand.RecordAdminPayment("rest",1,2,50))
        expect(300,0)
        run(SettlementCommand.RecordAdminPayment("advance",1,2,40))
        expect(300,0) // Overpayment is a credit, not a negative amount to pay.
    }

    @Test fun `reopening cancellation and corrections update cost without losing completed payments`() {
        setup();training(-1,150,450)
        run(SettlementCommand.RecordAdminPayment("p",1,2,100))
        expect(300,50)
        run(SettlementCommand.ReopenTraining("t",version()));expect(0,0)
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,175))
        run(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_PAID,425))
        run(SettlementCommand.FinishTraining("t",version()));expect(300,25)
        run(SettlementCommand.ReopenTraining("t",version()))
        run(SettlementCommand.CancelTraining("t",version()));expect(0,0)
        run(SettlementCommand.RestoreTraining("t",version()));expect(0,0)
        run(SettlementCommand.FinishTraining("t",version()));expect(300,25)
    }

    @Test fun `credits in another group do not reduce remaining amount or mix accounts`() {
        setup();training(-1,300,100);training(-2,0,400)
        expect(400,200) // +100 in the first group, -200 in the second.
        assertEquals(100.toBigInteger(),service.myTrainings(2).remainingToPay)
        val first=service.myTrainings(1)
        val last=service.myTrainings(1,99)
        assertEquals(first.participationCost,last.participationCost)
        assertEquals(first.remainingToPay,last.remainingToPay)
    }

    @Test fun `cross group money totals do not overflow Long`() {
        setup();training(-1,0,Long.MAX_VALUE,minutes2=0);training(-2,0,Long.MAX_VALUE,minutes2=0)
        val summary=service.myTrainings(1)
        assertEquals(Long.MAX_VALUE.toBigInteger()*2.toBigInteger(),summary.participationCost)
        assertEquals(summary.participationCost,summary.remainingToPay)
    }

    @ParameterizedTest
    @ValueSource(longs=[0,30,90])
    fun `disabled time counts one personal hour regardless of saved minutes or guests`(savedMinutes:Long) {
        setup()
        run(SettlementCommand.CreateTraining("t","Пример","2026-11-06","18:30"))
        run(SettlementCommand.AddPlayers("t",version(),listOf(1,2)))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_MINUTES,savedMinutes))
        run(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.GUEST,2))
        if(savedMinutes>0) run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_GUEST_MINUTES,savedMinutes))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,600))
        service.setGroupTrainingRule(Access(-1,1,true),"time",false,"disable-time")
        run(SettlementCommand.FinishTraining("t",version()))
        val summary=service.myTrainings(1)
        val closed=summary.page.items.single()
        val player=closed.players.single { it.userId==1L }
        assertEquals(savedMinutes,player.minutes)
        assertEquals(2,player.guestCount)
        assertEquals(60,summary.minutes)
        assertEquals(closed.rules.minutes(player),summary.minutes)
        assertEquals(1,summary.completedCount)
        // Changing the group default cannot rewrite an already accounted training's rules.
        service.setGroupTrainingRule(Access(-1,1,true),"time",true,"enable-time")
        assertEquals(60,service.myTrainings(1).minutes)
        service.database.verify()
    }

    @Test fun `mixed training rules and lifecycle keep personal totals consistent across groups`() {
        setup();training(-1,300,300,minutes2=90)
        service.setGroupTrainingRule(Access(-1,1,true),"time",false,"first-group-off")
        assertEquals(90,service.myTrainings(2).minutes)
        run(SettlementCommand.CreateTraining("t","Вторая","2026-11-06","18:30"),-2)
        run(SettlementCommand.AddPlayers("t",version(-2),listOf(1,2)),-2)
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300),-2)
        service.setGroupTrainingRule(Access(-2,1,true),"time",false,"second-group-off")
        assertEquals(90,service.myTrainings(2).minutes,"Open trainings do not affect the total")
        run(SettlementCommand.FinishTraining("t",version(-2)),-2)
        assertEquals(150,service.myTrainings(2).minutes)
        run(SettlementCommand.ReopenTraining("t",version(-2)),-2)
        assertEquals(90,service.myTrainings(2).minutes)
        run(SettlementCommand.CancelTraining("t",version(-2)),-2)
        assertEquals(90,service.myTrainings(2).minutes)
        service.setGroupTrainingRule(Access(-2,1,true),"time",true,"second-group-on")
        run(SettlementCommand.RestoreTraining("t",version(-2)),-2)
        assertEquals(90,service.myTrainings(2).minutes)
        run(SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_MINUTES,120),-2)
        run(SettlementCommand.FinishTraining("t",version(-2)),-2)
        assertEquals(210,service.myTrainings(2).minutes)
        assertEquals(2,service.myTrainings(2).completedCount)
        service.database.verify()
    }

    @Test fun `a nonplaying payer gets no personal hour when time is disabled`() {
        setup()
        service.setGroupTrainingRule(Access(-1,1,true),"time",false,"disable-time")
        run(SettlementCommand.CreateTraining("t","Пример","2026-11-06","18:30"))
        run(SettlementCommand.AddPlayers("t",version(),listOf(2)))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300))
        run(SettlementCommand.FinishTraining("t",version()))
        assertEquals(0,service.myTrainings(1).minutes)
        assertEquals(60,service.myTrainings(2).minutes)
        service.database.verify()
    }
}
