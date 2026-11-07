package ru.movereon.tennis.application

import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** The same write and ledger transition serve current commands and persisted legacy commands. */
internal data class PaymentMutation(val before:MoneyTransfer?,val after:MoneyTransfer) {
    val unchanged:Boolean get()=before==after
    val entries:List<BalanceEntry> get()=posted(before).reversedAmounts()+posted(after)
    private fun posted(t:MoneyTransfer?):List<BalanceEntry> = if(t?.status==PaymentStatus.ACTIVE)
        listOf(BalanceEntry(ParticipantId(t.from.toString()),t.amount),BalanceEntry(ParticipantId(t.to.toString()),-t.amount)) else emptyList()
}

internal class PaymentCommands(private val c:Connection,private val database:Database,private val clock:Clock,
    private val a:Access,private val admin:Boolean,private val known:(Long)->Unit) {
    fun execute(command:SettlementCommand,absent:AbsentAccount?):PaymentMutation {
        val change=when(command) {
            is SettlementCommand.SendPayment -> {
                known(command.to);checkId(command.id)
                val available=availablePayments(c,database,a.groupId).firstOrNull { it.from.value==a.userId.toString() && it.to.value==command.to.toString() }
                state(available!=null && available.amount==command.amount,"Платёж больше недоступен или сумма изменилась. Обнови список платежей")
                create(command.id,a.userId,command.to,command.amount,PaymentStatus.REVIEW)
            }
            is SettlementCommand.SendOtherPayment -> {
                known(command.to);checkId(command.id);validAmount(command.amount,a.userId,command.to)
                duplicates(a.userId,command.to,command.amount,command.allowSimilar)
                create(command.id,a.userId,command.to,command.amount,PaymentStatus.REVIEW)
            }
            is SettlementCommand.RecordAdminPayment -> {
                allowed(admin,"Это действие доступно администратору этой группы")
                known(command.from);known(command.to);checkId(command.id);validAmount(command.amount,command.from,command.to)
                create(command.id,command.from,command.to,command.amount,PaymentStatus.ACTIVE)
            }
            is SettlementCommand.ReceivePayment -> {
                val old=readTransfer(c,a.groupId,command.id)
                allowed(a.userId==old.to,"Принять платёж может только его получатель")
                if(old.status==PaymentStatus.ACTIVE) PaymentMutation(old,old) else {
                    command.version?.let { stale(old,it) }
                    state(old.status==PaymentStatus.REVIEW,"Этот платёж недоступен для принятия")
                    update(old,old.copy(status=PaymentStatus.ACTIVE,reviewer=null,reviewParty=null))
                }
            }
            is SettlementCommand.CancelPayment -> {
                val old=readTransfer(c,a.groupId,command.id)
                allowed(admin || old.from==a.userId && old.status==PaymentStatus.REVIEW,
                    "Отменить подтверждённый или чужой перевод может только администратор группы")
                stale(old,command.version);state(old.status!=PaymentStatus.CANCELLED,"Перевод уже отменён")
                update(old,old.copy(status=PaymentStatus.CANCELLED,reviewer=null,reviewParty=null))
            }
            is SettlementCommand.EditPaymentAmount -> {
                val old=readTransfer(c,a.groupId,command.id)
                allowed(canEditPayment(a,old,admin),"Изменить сумму может отправитель до подтверждения или администратор группы")
                stale(old,command.version);require(command.amount>0) { "Укажи положительную сумму" }
                update(old,old.copy(amount=command.amount))
            }
            else -> legacy(command,absent)
        }
        if(!change.unchanged) persist(change)
        return change
    }

    /** Compatibility policy only: legacy identity, date, duplicate and review rules are preserved. */
    private fun legacy(command:SettlementCommand,absent:AbsentAccount?):PaymentMutation=when(command) {
        is SettlementCommand.RecordTransfer -> {
            checkId(command.id);known(command.from);known(command.to);validAmount(command.amount,command.from,command.to)
            LocalDate.parse(command.date);require(command.note.length<=300) { "Комментарий не длиннее 300 символов" }
            party(setOf(command.from,command.to),command.onBehalfOf,absent)
            duplicates(command.from,command.to,command.amount,command.allowSimilar)
            require(sqlQuery(c,"SELECT COUNT(*) FROM transfers WHERE group_id=? AND id=?",a.groupId,command.id) { it.getInt(1) }.single()==0) { "Перевод уже записан" }
            create(command.id,command.from,command.to,command.amount,PaymentStatus.ACTIVE,command.date,command.note)
        }
        is SettlementCommand.EditTransferAmount -> {
            val old=legacyTransfer(command.id)
            party(setOf(old.from,old.to),null,null);stale(old,command.version)
            state(old.status!=PaymentStatus.CANCELLED,"Отменённый перевод нельзя исправить")
            require(command.amount>0) { "Укажи положительную сумму" }
            if(command.amount!=old.amount) duplicates(old.from,old.to,command.amount,command.allowSimilar,old.id)
            update(old,old.copy(amount=command.amount))
        }
        is SettlementCommand.ChangeTransfer -> {
            val old=legacyTransfer(command.id);stale(old,command.version)
            val party=party(setOf(old.from,old.to),command.onBehalfOf,absent)
            val status=when(command.change) {
                TransferChange.REVIEW -> {
                    state(old.status==PaymentStatus.ACTIVE,"Перевод уже уточняется или отменён")
                    PaymentStatus.REVIEW
                }
                TransferChange.CONFIRM,TransferChange.CANCEL -> {
                    state(old.status==PaymentStatus.REVIEW,"Сначала выбери «Уточнить перевод»")
                    allowed(old.reviewer==a.userId && old.reviewParty==party,"Завершить уточнение может тот, кто его начал")
                    if(command.change==TransferChange.CONFIRM) PaymentStatus.ACTIVE else PaymentStatus.CANCELLED
                }
            }
            update(old,old.copy(status=status,reviewer=a.userId.takeIf { status==PaymentStatus.REVIEW },reviewParty=party.takeIf { status==PaymentStatus.REVIEW }))
        }
        else -> error("Unhandled payment command")
    }

    private fun create(id:String,from:Long,to:Long,amount:Long,status:PaymentStatus,date:String=paymentDate(),note:String="")=
        PaymentMutation(null,MoneyTransfer(a.groupId,id,from,to,amount,date,note,status,1,
            a.userId.takeIf { status==PaymentStatus.REVIEW },a.userId.takeIf { status==PaymentStatus.REVIEW },a.userId))
    private fun update(old:MoneyTransfer,changed:MoneyTransfer)=PaymentMutation(old,
        if(changed==old) old else changed.copy(version=Math.addExact(old.version,1)))
    private fun persist(change:PaymentMutation) {
        val t=change.after
        if(change.before==null) insertTransfer(c,t.groupId,t.id,t.from,t.to,t.amount,t.date,t.createdBy,
            clock.instant().toString(),t.status,t.note,t.reviewer,t.reviewParty)
        else sqlUpdate(c,"UPDATE transfers SET amount=?,status=?,version=?,reviewer=?,review_party=? WHERE group_id=? AND id=?",
            t.amount,t.status.name,t.version,t.reviewer,t.reviewParty,t.groupId,t.id)
    }
    private fun legacyTransfer(id:String):MoneyTransfer {
        val old=readTransfer(c,a.groupId,id)
        state(sqlQuery(c,"SELECT COUNT(*) FROM actions WHERE group_id=? AND transfer_id=? AND kind IN ('SendPayment','SendOtherPayment','RecordAdminPayment')",a.groupId,id) { it.getInt(1) }.single()==0,
            "Используй новое меню «Мои финансы»")
        return old
    }
    private fun party(parties:Set<Long>,onBehalfOf:Long?,absent:AbsentAccount?):Long {
        if(onBehalfOf==null) { allowed(a.userId in parties,"Записать или уточнить перевод может одна из его сторон");return a.userId }
        allowed(admin,"Это действие доступно администратору этой группы")
        allowed(onBehalfOf in parties && absent==AbsentAccount(a.groupId,onBehalfOf),"Представлять можно только участника, чей выход из этой группы проверен через Telegram")
        return onBehalfOf
    }
    private fun duplicates(from:Long,to:Long,amount:Long,allow:Boolean,exclude:String="") {
        val ids=recentSimilarTransfers(c,a.groupId,from,to,amount,clock,exclude)
        if(ids.isNotEmpty() && !allow) throw DuplicateTransfer(ids)
    }
    private fun paymentDate()=LocalDate.now(clock.withZone(ZoneId.of(
        sqlQuery(c,"SELECT time_zone FROM groups WHERE id=?",a.groupId) { it.getString(1) }.single()))).toString()
    private fun validAmount(amount:Long,from:Long,to:Long)=require(amount>0 && from!=to) { "Укажи положительную сумму и разных участников" }
    private fun stale(old:MoneyTransfer,version:Long)=checkAccounting(old.version==version,ErrorCode.STALE_VERSION,"Данные изменились. Открой тренировку или перевод заново")
    private fun state(condition:Boolean,message:String)=checkAccounting(condition,ErrorCode.INVALID_STATE,message)
    private fun allowed(condition:Boolean,message:String)=checkAccounting(condition,ErrorCode.FORBIDDEN,message)
}

internal fun recentSimilarTransfers(c:Connection,group:Long,from:Long,to:Long,amount:Long,clock:Clock,exclude:String=""):List<String> =
    sqlQuery(c,"""SELECT id FROM transfers WHERE group_id=? AND from_user=? AND to_user=? AND amount=?
        AND id<>? AND status<>'CANCELLED' AND julianday(created_at)>=julianday(?)
        ORDER BY created_at DESC,id LIMIT 8""",group,from,to,amount,exclude,clock.instant().minusSeconds(86400).toString()) { it.getString(1) }
