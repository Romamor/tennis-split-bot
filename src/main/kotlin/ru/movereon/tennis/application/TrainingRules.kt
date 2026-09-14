package ru.movereon.tennis.application

import kotlinx.serialization.Serializable
import ru.movereon.tennis.core.*

/** Live rules for open trainings; accounted/cancelled trainings retain their last rules. */
@Serializable data class TrainingRules(val guestsEnabled:Boolean=true,val trackTime:Boolean=true) {
    val initialMinutes:Long get()=if(trackTime) 0 else 60
    fun minutes(row:Attendance):Long=if(trackTime) row.minutes else 60
    fun guestMinutes(row:Attendance):Long=if(trackTime) row.guestMinutes else 60
    fun requireChange(change:AttendanceChange) {
        if(change in setOf(AttendanceChange.ADJUST_MINUTES,AttendanceChange.SET_MINUTES))
            checkAccounting(trackTime,ErrorCode.INVALID_STATE,"В этой тренировке индивидуальный учёт времени выключен")
    }
    fun validate(row:Attendance,previous:Attendance?=null) {
        checkAccounting(guestsEnabled || row.guestCount<=(previous?.guestCount ?: 0),ErrorCode.INVALID_STATE,"В этой тренировке добавление гостей выключено")
        val savedMinutes=previous?.takeIf { it.playing }?.minutes ?: initialMinutes
        checkAccounting(trackTime || !row.playing || row.minutes==savedMinutes,ErrorCode.INVALID_STATE,"В этой тренировке индивидуальный учёт времени выключен")
    }
    fun change(row:Attendance,command:SettlementCommand.ChangeAttendance):Attendance {
        requireChange(command.change)
        if(!guestsEnabled) {
            val adds=when(command.change) {
                AttendanceChange.ADJUST_GUESTS -> command.value>0
                AttendanceChange.GUEST -> command.value>row.guestCount
                AttendanceChange.SET_GUEST_MINUTES -> command.value>0 && row.guestCount==0
                else -> false
            }
            checkAccounting(!adds,ErrorCode.INVALID_STATE,"В этой тренировке добавление гостей выключено")
        }
        val changed=changeAttendance(row,command)
        val result=if(!trackTime && !row.playing && changed.playing)
            changed.copy(minutes=initialMinutes,guestMinutes=if(changed.guestCount>0) initialMinutes else 0) else changed
        return result.also { validate(it,row) }
    }
}

/** Both projections remain valid so turning individual time back on is always possible. */
internal fun validateTrainingDraft(training:TrainingRecord) {
    validateDraftTraining(training.calculation())
    if(!training.rules.trackTime)
        validateDraftTraining(training.copy(rules=training.rules.copy(trackTime=true)).calculation())
}
