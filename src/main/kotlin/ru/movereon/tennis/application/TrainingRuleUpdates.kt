package ru.movereon.tennis.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.storage.*
import java.sql.Connection
import java.time.Clock

/** Preserve raw attendance and ledger rows. Version changes invalidate earlier accounting previews. */
internal fun applyRulesToOpenTrainings(c:Connection,group:Long,actor:Long?,request:String,clock:Clock) {
    val rules=readGroupTrainingRules(c,group)
    val ids=sqlQuery(c,"SELECT id FROM trainings WHERE group_id=? AND status='OPEN' AND (guests_enabled<>? OR track_time<>?)",group,rules.guestsEnabled,rules.trackTime) { it.getString(1) }
    val json=Json { encodeDefaults=true }
    ids.forEach { id ->
        val old=readTraining(c,group,id)
        val updated=old.copy(rules=rules,version=Math.addExact(old.version,1))
        validateTrainingDraft(updated)
        sqlUpdate(c,"UPDATE trainings SET guests_enabled=?,track_time=?,version=? WHERE group_id=? AND id=?",rules.guestsEnabled,rules.trackTime,updated.version,group,id)
        sqlUpdate(c,"""INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,payload_json,before_json,after_json,result_version,occurred_at,needs_delivery)
            VALUES(?,?,?,?,?,'{}',?,?,?,?,1)""",group,"rules:$request:$id",actor ?: old.createdBy,
            if(actor==null) "SynchronizeTrainingRules" else "UpdateTrainingRules",id,
            json.encodeToString(old),json.encodeToString(updated),updated.version,clock.instant().toString())
    }
}
