package ru.movereon.tennis.application

import ru.movereon.tennis.core.*

/** Pure input transformations, shared by live commands and saved input recovery. */
internal fun changeAttendance(row: Attendance, command: SettlementCommand.ChangeAttendance): Attendance {
    fun participating() = checkAccounting(row.playing,ErrorCode.INVALID_STATE, "Сначала присоединись к тренировке")
    fun adjusted(value:Long,delta:Long):Long = try { Math.addExact(value,delta).coerceAtLeast(0) }
        catch(_:ArithmeticException) { throw AccountingException(ErrorCode.OUT_OF_RANGE,"Слишком большое значение") }
    val changed=when (command.change) {
        AttendanceChange.JOIN -> if(row.playing) row else row.copy(playing=true,minutes=0)
        AttendanceChange.LEAVE -> row.copy(playing=false,minutes=0,guestMinutes=0,guestCount=0)
        AttendanceChange.LEAVE_AND_CLEAR_PAYMENT -> {
            checkAccounting(row.paid==command.value,ErrorCode.STALE_VERSION,"Оплата изменилась. Открой форму заново")
            row.copy(playing=false,minutes=0,guestMinutes=0,guestCount=0,paid=0)
        }
        AttendanceChange.MARK_PAID -> {
            participating()
            if(row.paid==0L) row.copy(paid=300) else row
        }
        AttendanceChange.ADJUST_PAID -> {
            require(command.value in listOf(-1000L,-100L,-50L,-10L,-5L,-1L,1L,5L,10L,50L,100L,1000L))
            checkAccounting(row.playing || row.paid>0,ErrorCode.INVALID_STATE,"Сначала присоединись к тренировке")
            row.copy(paid=adjusted(row.paid,command.value))
        }
        AttendanceChange.SET_PAID -> {
            require(command.value>=0)
            row.copy(paid=command.value)
        }
        AttendanceChange.ADJUST_MINUTES -> {
            require(command.value in listOf(-60L,-30L,30L,60L));participating()
            row.copy(minutes=adjusted(row.minutes,command.value))
        }
        AttendanceChange.SET_MINUTES -> {
            require(command.value>=0 && command.value%30==0L);participating()
            row.copy(minutes=command.value)
        }
        AttendanceChange.GUEST, AttendanceChange.ADJUST_GUESTS -> {
            participating()
            val count=if(command.change==AttendanceChange.GUEST) command.value else {
                require(command.value in listOf(-1L,1L));row.guestCount.toLong()+command.value
            }
            require(count in 0L..99L) { "Можно добавить от 0 до 99 гостей" }
            row.copy(guestCount=count.toInt())
        }
        AttendanceChange.SET_GUEST_MINUTES -> {
            // An old button cannot give a guest a different duration under the new rules.
            require(command.value==row.minutes || command.value==0L) { "Гости играют столько же, сколько пригласивший" }
            participating();row.copy(guestCount=if(command.value==0L) 0 else maxOf(1,row.guestCount))
        }
    }
    return if(changed==row) row else changed.copy(guestMinutes=if(changed.guestCount>0) changed.minutes else 0)
}

