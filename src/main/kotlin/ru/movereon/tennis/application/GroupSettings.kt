package ru.movereon.tennis.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.core.*
import java.time.Clock

/** Coordinates atomic preference changes and updates all open trainings. */
internal class GroupSettings(private val database:Database,private val clock:Clock) {
    private val json=Json { encodeDefaults=true }
    fun setGroupTrainingRule(a:Access,field:String,value:Boolean,requestId:String=java.util.UUID.randomUUID().toString())=database.write { c ->
        checkAccounting(isPresentGroupMember(c,a),ErrorCode.FORBIDDEN,"Доступ только участникам этой группы")
        checkAccounting(isGroupAdmin(c,a),ErrorCode.FORBIDDEN,"Это действие доступно администратору этой группы")
        val column=when(field) { "guests"->"guests_enabled";"time"->"track_time";else->throw AccountingException(ErrorCode.INVALID_INPUT,"Неизвестная настройка") }
        val request="group-rules:$requestId"
        val payload="${field}:${value}"
        val prior=sqlQuery(c,"SELECT actor_id,payload_json FROM actions WHERE group_id=? AND request_id=?",a.groupId,request) { it.getLong(1) to it.getString(2) }.singleOrNull()
        if(prior!=null) {
            checkAccounting(prior==(a.userId to json.encodeToString(payload)),ErrorCode.COMMAND_CONFLICT,"Этот запрос уже использован")
            return@write
        }
        val before=json.encodeToString(readGroupTrainingRules(c,a.groupId))
        sqlUpdate(c,"UPDATE groups SET $column=? WHERE id=?",value,a.groupId)
        applyRulesToOpenTrainings(c,a.groupId,a.userId,request,clock)
        sqlUpdate(c,"""INSERT INTO actions(group_id,request_id,actor_id,kind,payload_json,before_json,after_json,result_version,occurred_at,needs_delivery)
            VALUES(?,?,?,'SetGroupTrainingRule',?,?,?,1,?,0)""",a.groupId,request,a.userId,json.encodeToString(payload),before,
            json.encodeToString(readGroupTrainingRules(c,a.groupId)),clock.instant().toString())
    }
    /** Adopt group settings on upgrade/restart without inventing or overwriting attendance. */
    fun synchronizeOpenTrainingRules()=database.write { c ->
        val groups=sqlQuery(c,"""SELECT DISTINCT t.group_id FROM trainings t JOIN groups g ON g.id=t.group_id
            WHERE t.status='OPEN' AND (t.guests_enabled<>g.guests_enabled OR t.track_time<>g.track_time)""") { it.getLong(1) }
        groups.forEach { applyRulesToOpenTrainings(c,it,null,java.util.UUID.randomUUID().toString(),clock) }
    }
}
