package ru.movereon.tennis.selfservice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.storage.sqlQuery
import ru.movereon.tennis.storage.sqlUpdate
import ru.movereon.tennis.telegram.TgUpdate
import java.time.Clock

data class PendingUpdate(val id:Long,val update:TgUpdate?,val plan:EventPlan?)

/** Telegram acknowledges durable receipt; local retries retain each author's event order. */
class UpdateInbox(private val database:Database,private val clock:Clock=Clock.systemUTC()) {
    private val json=Json { encodeDefaults=true;ignoreUnknownKeys=true }

    fun accept(updates:List<TgUpdate>) {
        if(updates.isEmpty()) return
        // Encode before the transaction: an unencodable response must never advance its cursor.
        val received=updates.sortedBy { it.id }.map { Triple(it,json.encodeToString(it),orderingKey(it)) }
        val next=Math.addExact(received.last().first.id,1L)
        database.write { c ->
            val previous=sqlQuery(c,"SELECT next_update_id,received_order FROM bot_polling WHERE singleton=1") {
                it.getString(1)?.toLong() to it.getLong(2)
            }.single()
            var sequence=previous.second
            received.forEach { (update,payload,key) ->
                val following=Math.addExact(sequence,1L)
                val inserted=sqlUpdate(c,"""INSERT INTO bot_events(update_id,update_json,ordering_key,received_order,next_attempt_at)
                    VALUES(?,?,?,?,?) ON CONFLICT(update_id) DO NOTHING""",update.id,payload,key,following,clock.millis())
                if(inserted==1) sequence=following
                else sqlUpdate(c,"""UPDATE bot_events SET update_json=COALESCE(update_json,?),
                    ordering_key=COALESCE(ordering_key,?) WHERE update_id=? AND completed=0""",payload,key,update.id)
            }
            sqlUpdate(c,"UPDATE bot_polling SET next_update_id=?,received_order=? WHERE singleton=1",
                maxOf(previous.first ?: next,next),sequence)
        }
    }

    fun offset():Long?=database.read { c ->
        sqlQuery(c,"SELECT next_update_id FROM bot_polling WHERE singleton=1") { it.getString(1)?.toLong() }.single()
    }

    fun pending(limit:Int=20):List<PendingUpdate> {
        require(limit>0)
        return database.read { c ->
            sqlQuery(c,"""SELECT e.update_id,e.update_json,e.plan_json FROM bot_events e
                WHERE e.completed=0 AND e.next_attempt_at<=? AND $head
                ORDER BY e.next_attempt_at,e.received_order,e.update_id LIMIT ?""",clock.millis(),limit) {
                PendingUpdate(it.getLong(1),it.getString(2)?.let { raw -> json.decodeFromString<TgUpdate>(raw) },
                    it.getString(3)?.let { raw -> json.decodeFromString<EventPlan>(raw) })
            }
        }
    }

    fun retry(id:Long,delayMillis:Long) {
        require(delayMillis>=0)
        val at=Math.addExact(clock.millis(),delayMillis)
        database.write { c -> sqlUpdate(c,"UPDATE bot_events SET next_attempt_at=? WHERE update_id=? AND completed=0",at,id) }
    }

    /** Seconds until the first eligible author's head; ready work bypasses long polling. */
    fun nextDelay(maximum:Int):Int {
        require(maximum>=0)
        if(maximum==0) return 0
        return database.read { c ->
            val at=sqlQuery(c,"SELECT MIN(e.next_attempt_at) FROM bot_events e WHERE e.completed=0 AND $head") {
                it.getString(1)?.toLong()
            }.single() ?: return@read maximum
            val remaining=(at-clock.millis()).coerceAtLeast(0)
            val seconds=if(remaining==0L) 0L else (remaining-1)/1000+1
            minOf(maximum.toLong(),seconds).toInt()
        }
    }

    fun hasPendingVotes(telegramPollId:String):Boolean=database.read { c ->
        sqlQuery(c,"""SELECT EXISTS(SELECT 1 FROM bot_events WHERE completed=0
            AND json_extract(update_json,'$.poll_answer.poll_id')=?)""",telegramPollId) { it.getBoolean(1) }.single()
    }

    private fun orderingKey(update:TgUpdate):String {
        val user=update.callback?.from ?: update.message?.from ?: update.pollAnswer?.user
        return user?.let { "user:${it.id}" } ?: "event:${update.id}"
    }

    private companion object {
        // Legacy plans predate receipt sequencing and have received_order=0. Their actor is still known.
        fun key(alias:String)="COALESCE($alias.ordering_key,'user:' || json_extract($alias.plan_json,'$.user'),'event:' || $alias.update_id)"
        const val earlierOrder="""(earlier.received_order<e.received_order OR
            (earlier.received_order=e.received_order AND earlier.update_id<e.update_id))"""
        // Keep the normal lookup indexable; computing the earlier key for every row scans the whole inbox.
        val head="""NOT EXISTS(SELECT 1 FROM bot_events earlier WHERE earlier.completed=0
            AND earlier.ordering_key=${key("e")} AND $earlierOrder)
            AND NOT EXISTS(SELECT 1 FROM bot_events earlier WHERE earlier.completed=0
            AND earlier.ordering_key IS NULL AND ${key("earlier")}=${key("e")} AND $earlierOrder)"""
    }
}
