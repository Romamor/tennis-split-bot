package ru.movereon.tennis.application

import kotlinx.serialization.Serializable
import ru.movereon.tennis.core.*

/** Supplied by the Telegram boundary after a live membership check, never by callback payload. */
data class Access(val groupId:Long,val userId:Long,val telegramAdmin:Boolean=false) {
    init { require(groupId<0 && userId>0) }
}
data class AbsentAccount(val groupId:Long,val userId:Long)
@Serializable data class Account(val id:Long,val firstName:String,val lastName:String="",val username:String?=null,val isBot:Boolean=false) {
    val name:String get()=(firstName+" "+lastName).trim().ifEmpty { "Участник $id" }
}
@Serializable data class SettlementGroup(val id:Long,val title:String,val timeZone:String)
data class AccountBalance(val account:Account,val balance:Long,val attendance:Int,val present:Boolean,val hasPlayed:Boolean=attendance>0)
enum class GroupRole { SUPERADMIN,ADMIN,MEMBER }
data class GroupRoleEntry(val account:Account,val role:GroupRole)
@Serializable enum class TrainingPhase { OPEN,CLOSED,REVIEW,CANCELLED }
@Serializable data class Attendance(val userId:Long,val playing:Boolean,val minutes:Long=60,val guestMinutes:Long=0,val paid:Long=0,val ordinal:Int=0,val appliedPlaying:Boolean=false)
@Serializable data class TrainingRecord(val groupId:Long,val id:String,val title:String,val date:String,val startTime:String,
    val phase:TrainingPhase,val version:Long,val appliedVersion:Long,val createdBy:Long,val players:List<Attendance>) {
    fun calculation():Training = Training(players.sortedBy { it.ordinal }.filter { it.playing }.flatMap {
        listOf(PlayerSlot(ParticipantId(it.userId.toString()),it.minutes)) + if(it.guestMinutes>0) listOf(PlayerSlot(ParticipantId(it.userId.toString()),it.guestMinutes,true)) else emptyList()
    },players.filter { it.paid>0 }.map { ExpensePayment(ParticipantId(it.userId.toString()),it.paid) })
}
@Serializable enum class PaymentStatus { ACTIVE,REVIEW,CANCELLED }
@Serializable data class MoneyTransfer(val groupId:Long,val id:String,val from:Long,val to:Long,val amount:Long,val date:String,val note:String,
    val status:PaymentStatus,val version:Long,val reviewer:Long?,val reviewParty:Long?,val createdBy:Long)
@Serializable data class ActionReceipt(val id:Long,val version:Long)
@Serializable data class AuditAction(val id:Long,val groupId:Long,val actorId:Long,val kind:String,val trainingId:String?,val transferId:String?,
    val before:String?,val after:String,val occurredAt:String)
data class Page<T>(val items:List<T>,val total:Int,val index:Int,val size:Int=8) { val pages:Int get()=maxOf(1,(total+size-1)/size) }
@Serializable enum class AttendanceChange { JOIN,LEAVE,LEAVE_AND_CLEAR_PAYMENT,MARK_PAID,ADJUST_PAID,SET_PAID,ADJUST_MINUTES,SET_MINUTES,GUEST,SET_GUEST_MINUTES }
@Serializable enum class TransferChange { REVIEW,CONFIRM,CANCEL }
@Serializable sealed interface SettlementCommand {
    @Serializable data class CreateTraining(val id:String,val title:String,val date:String,val startTime:String):SettlementCommand
    @Serializable data class EditTraining(val id:String,val version:Long,val title:String,val date:String,val startTime:String):SettlementCommand
    @Serializable data class ChangeAttendance(val id:String,val userId:Long,val change:AttendanceChange,val value:Long=0):SettlementCommand
    @Serializable data class SaveAttendance(val id:String,val userId:Long,val expected:Attendance?,val attendance:Attendance):SettlementCommand
    @Serializable data class AddPlayers(val id:String,val version:Long,val users:List<Long>):SettlementCommand
    @Serializable data class FinishTraining(val id:String,val version:Long):SettlementCommand
    @Serializable data class ReopenTraining(val id:String,val version:Long):SettlementCommand
    @Serializable data class CancelTraining(val id:String,val version:Long):SettlementCommand
    @Serializable data class SetAdministrator(val userId:Long,val enabled:Boolean):SettlementCommand
    @Serializable data class RecordTransfer(val id:String,val from:Long,val to:Long,val amount:Long,val date:String,val note:String="",val onBehalfOf:Long?=null,val allowSimilar:Boolean=false):SettlementCommand
    @Serializable data class ChangeTransfer(val id:String,val version:Long,val change:TransferChange,val onBehalfOf:Long?=null):SettlementCommand
    @Serializable data class EditTransferAmount(val id:String,val version:Long,val amount:Long,val allowSimilar:Boolean=false):SettlementCommand
}
class DuplicateTransfer(val ids:List<String>):IllegalStateException("Похожий перевод уже есть")
