package ru.movereon.tennis.application

import kotlinx.serialization.Serializable
import ru.movereon.tennis.core.*

/** Rules fixed for a training; group settings provide defaults when it is created. */
@Serializable data class TrainingRules(val guestsEnabled:Boolean=true,val trackTime:Boolean=true) {
    val initialMinutes:Long get()=if(trackTime) 0 else 60
    fun requireChange(change:AttendanceChange) {
        if(change in setOf(AttendanceChange.ADJUST_MINUTES,AttendanceChange.SET_MINUTES))
            checkAccounting(trackTime,ErrorCode.INVALID_STATE,"В этой тренировке индивидуальный учёт времени выключен")
        if(change in setOf(AttendanceChange.GUEST,AttendanceChange.ADJUST_GUESTS,AttendanceChange.SET_GUEST_MINUTES))
            checkAccounting(guestsEnabled,ErrorCode.INVALID_STATE,"В этой тренировке добавление гостей выключено")
    }
    fun validate(row:Attendance) {
        checkAccounting(guestsEnabled || row.guestCount==0 && row.guestMinutes==0L,ErrorCode.INVALID_STATE,"В этой тренировке добавление гостей выключено")
        checkAccounting(trackTime || !row.playing || row.minutes==60L,ErrorCode.INVALID_STATE,"В этой тренировке индивидуальный учёт времени выключен")
    }
    fun change(row:Attendance,command:SettlementCommand.ChangeAttendance):Attendance {
        requireChange(command.change)
        val result=changeAttendance(row,command)
        return (if(!trackTime && result.playing) result.copy(minutes=60,guestMinutes=if(result.guestCount>0) 60 else 0) else result)
            .also(::validate)
    }
}
