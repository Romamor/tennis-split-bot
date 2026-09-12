package ru.movereon.tennis.storage

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.movereon.tennis.application.SettlementService
import ru.movereon.tennis.core.*

/** Runs in the schema transaction; existing audit records and money transfers stay immutable. */
internal fun migrateTrainingStates(c:Connection, database:Database) {
    val json=Json { encodeDefaults=true }
    val service=SettlementService(database)
    val pending=sqlQuery(c,"SELECT group_id,id FROM trainings WHERE status='REVIEW'") { it.getLong(1) to it.getString(2) }
    for((group,id) in pending) {
        // Read the retired state through the current model without accepting it in new operations.
        sqlUpdate(c,"UPDATE trainings SET status='OPEN' WHERE group_id=? AND id=?",group,id)
        val old=service.training(c,group,id)
        val before=JsonObject(json.encodeToJsonElement(old).jsonObject+("phase" to JsonPrimitive("REVIEW"))).toString()
        val entries=database.trainingEffect(c,group,id).filterValues { it!=0L }.map { (user,amount) ->
            BalanceEntry(ParticipantId(user.toString()),Math.negateExact(amount))
        }
        applyEntries(database.balances(c,group).mapKeys { ParticipantId(it.key.toString()) },entries)
        val version=Math.addExact(old.version,1)
        sqlUpdate(c,"UPDATE trainings SET applied_version=0,version=? WHERE group_id=? AND id=?",version,group,id)
        sqlUpdate(c,"UPDATE training_players SET applied_playing=0 WHERE group_id=? AND training_id=?",group,id)
        val after=json.encodeToString(service.training(c,group,id))
        sqlUpdate(c,"""INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,payload_json,before_json,after_json,result_version,occurred_at,needs_delivery)
            VALUES(?,?,?,'MigrateTrainingState',?,?,?,?,?,?,1)""",group,"migration:v5:$id",old.createdBy,id,
            "{\"schema\":5,\"automatic\":true}",before,after,version,Instant.now().toString())
        val action=sqlQuery(c,"SELECT last_insert_rowid()") { it.getLong(1) }.single()
        entries.forEachIndexed { index,entry ->
            sqlUpdate(c,"INSERT INTO balance_entries(action_id,group_id,entry_index,user_id,amount) VALUES(?,?,?,?,?)",action,group,index,entry.participant.value.toLong(),entry.amount)
        }
    }
    c.createStatement().use { s ->
        s.execute("ALTER TABLE trainings ADD COLUMN status_next TEXT NOT NULL DEFAULT 'OPEN' CHECK(status_next IN ('OPEN','CLOSED','CANCELLED'))")
        s.execute("UPDATE trainings SET status_next=status")
        s.execute("ALTER TABLE trainings DROP COLUMN status")
        s.execute("ALTER TABLE trainings RENAME COLUMN status_next TO status")
        s.execute("PRAGMA user_version=5")
    }
    refreshAttendance(c)
    // The editor moved to public cards. Refresh known messages of every phase once,
    // including historical closed/cancelled cards that normal startup skips.
    sqlUpdate(c,"""UPDATE actions SET delivered_at=NULL WHERE id IN (
        SELECT MAX(a.id) FROM actions a JOIN bot_deliveries d
        ON d.delivery_key='training:' || a.group_id || ':' || a.training_id
        WHERE d.status='SENT' AND d.message_id IS NOT NULL AND a.needs_delivery=1
        GROUP BY a.group_id,a.training_id)""")
}
