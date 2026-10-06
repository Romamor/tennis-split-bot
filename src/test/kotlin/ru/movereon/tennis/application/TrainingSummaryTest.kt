package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
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
}
