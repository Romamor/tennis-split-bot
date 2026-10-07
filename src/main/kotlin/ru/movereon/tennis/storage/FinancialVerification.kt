package ru.movereon.tennis.storage

import java.math.BigInteger
import java.sql.Connection
import kotlinx.serialization.json.Json
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.calculateTraining

internal data class FinancialVerification(val operations:Int,val trainings:Int,val transfers:Int)
private data class FinancialSource(val group:Long,val id:String,val training:Boolean) {
    override fun toString()="${if(training) "Тренировка" else "Перевод"} $id, группа $group"
}

/** Read-only reconciliation within the caller's consistent SQLite snapshot. Never used on the button path. */
internal fun verifyFinancialLedger(c:Connection):FinancialVerification {
    val effects=linkedMapOf<FinancialSource,MutableMap<Long,BigInteger>>()
    val operations=linkedMapOf<Long,BigInteger>()
    sqlEach(c,"""SELECT e.action_id,e.group_id,e.user_id,e.amount,a.training_id,a.transfer_id
        FROM balance_entries e JOIN actions a ON a.id=e.action_id""") { r ->
        val action=r.getLong(1);val amount=r.getLong(4).toBigInteger()
        operations[action]=(operations[action] ?: BigInteger.ZERO)+amount
        val training=r.getString(5);val transfer=r.getString(6)
        check((training==null)!=(transfer==null)) { "Проводка операции $action не связана ровно с одной тренировкой или переводом" }
        val source=FinancialSource(r.getLong(2),training ?: transfer,training!=null)
        val accounts=effects.getOrPut(source) { linkedMapOf() };val user=r.getLong(3)
        accounts[user]=(accounts[user] ?: BigInteger.ZERO)+amount
    }
    operations.forEach { (id,total) -> check(total==BigInteger.ZERO) { "Несбалансированная операция $id: $total ₽" } }

    fun reconcile(source:FinancialSource,expected:Map<Long,BigInteger>) {
        val actual=effects.remove(source).orEmpty()
        for(user in (expected.keys+actual.keys).sorted()) {
            val wanted=expected[user] ?: BigInteger.ZERO;val found=actual[user] ?: BigInteger.ZERO
            check(wanted==found) { "$source: участник $user, ожидается $wanted ₽, проводки $found ₽" }
        }
    }
    val transfers=sqlQuery(c,"SELECT group_id,id,from_user,to_user,amount,status FROM transfers") { r ->
        val expected=if(r.getString(6)=="ACTIVE") mapOf(r.getLong(3) to r.getLong(5).toBigInteger(),r.getLong(4) to -r.getLong(5).toBigInteger()) else emptyMap()
        reconcile(FinancialSource(r.getLong(1),r.getString(2),false),expected)
    }.size

    // Older read-only backups lack optional rule/guest columns. Do not migrate them to verify.
    val playerColumns=sqlQuery(c,"PRAGMA table_info(training_players)") { it.getString("name") }.toSet()
    val trainingColumns=sqlQuery(c,"PRAGMA table_info(trainings)") { it.getString("name") }.toSet()
    val players=sqlQuery(c,"SELECT * FROM training_players ORDER BY group_id,training_id,ordinal") { r ->
        FinancialSource(r.getLong("group_id"),r.getString("training_id"),true) to
            Attendance(r.getLong("user_id"),r.getBoolean("playing"),r.getLong("minutes"),r.getLong("guest_minutes"),r.getLong("paid"),r.getInt("ordinal"),r.getBoolean("applied_playing"),
                if("guest_count" in playerColumns) r.getInt("guest_count") else if(r.getLong("guest_minutes")>0) 1 else 0)
    }.groupBy({ it.first },{ it.second })
    val schema=sqlQuery(c,"PRAGMA user_version") { it.getInt(1) }.single()
    val legacyJson=Json { ignoreUnknownKeys=true }
    val trainings=sqlQuery(c,"SELECT * FROM trainings") { r ->
        val source=FinancialSource(r.getLong("group_id"),r.getString("id"),true)
        val status=r.getString("status");val applied=r.getLong("applied_version")
        if(schema>=5) check(applied==if(status=="CLOSED") r.getLong("version") else 0L) { "$source: учтённая версия не соответствует статусу $status" }
        val accounted=when(status) {
            "CLOSED" -> TrainingRecord(source.group,source.id,r.getString("title"),r.getString("played_on"),r.getString("starts_at"),TrainingPhase.CLOSED,r.getLong("version"),applied,r.getLong("created_by"),players[source].orEmpty(),
                TrainingRules(if("guests_enabled" in trainingColumns) r.getBoolean("guests_enabled") else true,if("track_time" in trainingColumns) r.getBoolean("track_time") else true))
            "REVIEW" -> {
                check(schema<=4) { "$source: неизвестный статус $status" }
                val snapshot=sqlQuery(c,"SELECT after_json FROM actions WHERE group_id=? AND training_id=? AND kind='FinishTraining' AND result_version=? ORDER BY id DESC LIMIT 1",source.group,source.id,applied) { it.getString(1) }.singleOrNull()
                check(snapshot!=null) { "$source: отсутствует снимок учтённой версии $applied" }
                legacyJson.decodeFromString<TrainingRecord>(snapshot)
            }
            "OPEN","CANCELLED" -> null
            else -> error("$source: неизвестный статус $status")
        }
        val expected=linkedMapOf<Long,BigInteger>()
        if(accounted!=null) for(entry in calculateTraining(accounted.calculation()).entries) {
            val user=entry.participant.value.toLong()
            expected[user]=(expected[user] ?: BigInteger.ZERO)+entry.amount.toBigInteger()
        }
        reconcile(source,expected)
    }.size
    check(effects.isEmpty()) { "Проводки без финансового источника: ${effects.keys.first()}" }
    return FinancialVerification(operations.size,trainings,transfers)
}
