package ru.movereon.tennis.selfservice

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.application.SettlementCommand
import ru.movereon.tennis.application.Attendance
import ru.movereon.tennis.storage.*
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

@Serializable data class ScreenAction(val kind: String, val group: Long, val id: String = "", val page: Int = 0,
    val user: Long = 0, val value: Long = 0, val version: Long = 0, val option: String = "",
    val back: ScreenAction? = null, val resume: ScreenAction? = null)
@Serializable data class InputForm(val kind: String, val group: Long, val training: String = "", val user: Long = 0,
    val version: Long = 0, val title: String = "Теннис", val date: String = "", val time: String = "19:00",
    val amount: Long = 0, val direction: String = "out", val note: String = "", val request: Int = 0,
    val attendance: AttendanceDraft? = null, val selectedUsers: List<Long> = emptyList(), val page: Int = 0,
    val order: List<Long>? = null, val origin: ScreenAction? = null, val baseline: String? = null, val transfer: String = "",
    val similar: List<String> = emptyList())
@Serializable data class AttendanceDraft(val training: String, val user: Long, val expected: Attendance?, val value: Attendance,
    val returnPage: Int? = null, val origin: ScreenAction? = null)
@Serializable data class EventPlan(val user: Long, val chat: Long, val screen: ScreenAction,
    val command: SettlementCommand? = null, val form: InputForm? = null, val callback: String? = null,
    val ephemeral: Long? = null, val notice: String? = null,
    val newPrivateMessage: Boolean = false, val previousPrivateMessage: Long? = null,
    val draft: AttendanceDraft? = null, val clearDraft: Boolean = false, val clearDraftGroup: Long? = null)
data class ButtonRecord(val action: ScreenAction, val owner: Long?, val scope: String, val permanent: Boolean)
data class Delivery(val key: String, val chat: Long, val user: Long?, val message: Long?, val ephemeral: Long?, val status: String)

class InteractionStore(val database: Database, private val clock: Clock = Clock.systemUTC()) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun formSignature(form: InputForm): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(form).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun draftSignature(draft: AttendanceDraft): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(draft).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun attendanceDraft(user: Long, group: Long): AttendanceDraft? = form(user,group)?.attendance
    fun attendanceDraft(user: Long, group: Long, draft: AttendanceDraft?) = database.write { c ->
        val input=draft?.let { json.encodeToString(InputForm("attendance",group,training=it.training,user=it.user,attendance=it)) }
        sqlUpdate(c,"""INSERT INTO bot_sessions(user_id,chat_id,group_id,input_json) VALUES(?,?,?,?)
            ON CONFLICT(user_id,chat_id) DO UPDATE SET input_json=excluded.input_json""",user,group,group,input)
    }
    fun offset(): Long? = database.read { c ->
        sqlQuery(c, "SELECT MIN(update_id) FROM bot_events WHERE completed=0") { it.getString(1)?.toLong() }.single()
            ?: sqlQuery(c, "SELECT MAX(update_id)+1 FROM bot_events") { it.getString(1)?.toLong() }.single()
    }
    fun completed(id: Long) = database.read { c -> sqlQuery(c, "SELECT completed FROM bot_events WHERE update_id=?", id) { it.getBoolean(1) }.singleOrNull() == true }
    fun plan(id: Long): EventPlan? = database.read { c -> sqlQuery(c, "SELECT plan_json FROM bot_events WHERE update_id=?", id) { it.getString(1)?.let { text -> json.decodeFromString<EventPlan>(text) } }.singleOrNull() }
    fun plan(id: Long, plan: EventPlan) = database.write { c ->
        sqlUpdate(c, "INSERT INTO bot_events(update_id,user_id,group_id,plan_json) VALUES(?,?,?,?) ON CONFLICT(update_id) DO NOTHING",
            id, plan.user, plan.screen.group.takeIf { it < 0 }, json.encodeToString(plan))
    }
    fun complete(id: Long) = database.write { c ->
        sqlUpdate(c, "INSERT INTO bot_events(update_id,completed) VALUES(?,1) ON CONFLICT(update_id) DO UPDATE SET completed=1,plan_json=NULL", id)
    }
    fun form(user: Long, chat: Long): InputForm? = database.read { c ->
        sqlQuery(c, "SELECT input_json FROM bot_sessions WHERE user_id=? AND chat_id=?", user, chat) { it.getString(1)?.let { text -> json.decodeFromString<InputForm>(text) } }.singleOrNull()
    }
    fun selectedGroup(user: Long, chat: Long): Long? = database.read { c ->
        sqlQuery(c, "SELECT group_id FROM bot_sessions WHERE user_id=? AND chat_id=?", user, chat) { it.getString(1)?.toLong() }.singleOrNull()
    }
    fun session(user: Long, chat: Long, group: Long, form: InputForm?) = database.write { c ->
        sqlUpdate(c, """INSERT INTO bot_sessions(user_id,chat_id,group_id,input_json) VALUES(?,?,?,?)
            ON CONFLICT(user_id,chat_id) DO UPDATE SET group_id=excluded.group_id,
            input_json=CASE WHEN excluded.chat_id<0 THEN bot_sessions.input_json ELSE excluded.input_json END""",
            user, chat, group.takeIf { it < 0 }, form?.let { json.encodeToString(it) })
    }
    fun currentEphemeral(user: Long, chat: Long): Long? = database.read { c ->
        sqlQuery(c, "SELECT ephemeral_id FROM bot_sessions WHERE user_id=? AND chat_id=?", user, chat) { it.getString(1)?.toLong() }.singleOrNull()
    }
    fun rememberEphemeral(user: Long, chat: Long, id: Long?) = database.write { c ->
        sqlUpdate(c, "UPDATE bot_sessions SET ephemeral_id=? WHERE user_id=? AND chat_id=?", id, user, chat)
    }
    private val buttonConnection=ThreadLocal<java.sql.Connection>()
    fun <T> buttonBatch(block:()->T):T {
        if(buttonConnection.get()!=null) return block()
        return database.write { c ->
            buttonConnection.set(c)
            try { block() } finally { buttonConnection.remove() }
        }
    }
    private fun <T> buttonWrite(block:(java.sql.Connection)->T):T = buttonConnection.get()?.let(block) ?: database.write(block)
    fun button(action: ScreenAction, owner: Long?, scope: String, permanent: Boolean = false): String = buttonWrite { c ->
        val payload = json.encodeToString(action)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        val found = sqlQuery(c, """SELECT token FROM bot_buttons WHERE scope=? AND owner_id IS ? AND payload_hash=?
            AND action_json=? AND permanent=? AND (active=1 OR permanent=1 OR expires_at>?) LIMIT 1""", scope, owner, hash, payload, permanent, clock.instant().epochSecond) { it.getString(1) }.singleOrNull()
        found ?: UUID.randomUUID().toString().replace("-", "").also { token ->
            sqlUpdate(c, "INSERT INTO bot_buttons(token,group_id,owner_id,scope,permanent,payload_hash,action_json,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                token, action.group, owner, scope, permanent, hash, payload, clock.instant().epochSecond + 120)
        }
    }
    fun button(token: String): ButtonRecord? = database.read { c ->
        sqlQuery(c, "SELECT * FROM bot_buttons WHERE token=? AND (active=1 OR permanent=1 OR expires_at>?)", token, clock.instant().epochSecond) {
            ButtonRecord(json.decodeFromString(it.getString("action_json")), it.getString("owner_id")?.toLong(), it.getString("scope"), it.getBoolean("permanent"))
        }.singleOrNull()
    }
    /** Protection happens before the network request, so uncertain sends keep usable buttons. */
    fun protect(tokens: Set<String>) = database.write { c -> tokens.forEach { sqlUpdate(c, "UPDATE bot_buttons SET active=1 WHERE token=?", it) } }
    fun activeTokens(scope: String): Set<String> = database.read { c ->
        sqlQuery(c, "SELECT token FROM bot_buttons WHERE scope=? AND active=1", scope) { it.getString(1) }.toSet()
    }
    fun replace(scope: String, tokens: Set<String>) = database.write { c ->
        sqlUpdate(c, "UPDATE bot_buttons SET active=0,expires_at=? WHERE scope=? AND permanent=0", clock.instant().epochSecond + 120, scope)
        tokens.forEach { sqlUpdate(c, "UPDATE bot_buttons SET active=1 WHERE token=? AND scope=?", it, scope) }
    }
    fun cleanup() = database.write { c ->
        // Keep completed IDs for deduplication, but retire their no-longer-needed recovery plans.
        sqlUpdate(c,"UPDATE bot_events SET plan_json=NULL WHERE update_id IN (SELECT update_id FROM bot_events WHERE completed=1 AND plan_json IS NOT NULL LIMIT 1000)")
        sqlUpdate(c, "DELETE FROM bot_buttons WHERE token IN (SELECT token FROM bot_buttons WHERE active=0 AND permanent=0 AND expires_at<=? LIMIT 1000)", clock.instant().epochSecond)
    }
    fun delivery(key: String): Delivery? = database.read { c ->
        sqlQuery(c, "SELECT * FROM bot_deliveries WHERE delivery_key=?", key) {
            Delivery(key, it.getLong("chat_id"), it.getString("user_id")?.toLong(), it.getString("message_id")?.toLong(), it.getString("ephemeral_id")?.toLong(), it.getString("status"))
        }.singleOrNull()
    }
    fun sending(key: String, group: Long, chat: Long, user: Long?) = database.write { c ->
        sqlUpdate(c, """INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,user_id,status) VALUES(?,?,?,?,'SENDING')
            ON CONFLICT(delivery_key) DO UPDATE SET status='SENDING'""", key, group.takeIf { it < 0 }, chat, user)
    }
    fun deliveryResult(key: String, status: String, message: Long? = null, ephemeral: Long? = null) = database.write { c ->
        sqlUpdate(c, "UPDATE bot_deliveries SET status=?,message_id=COALESCE(?,message_id),ephemeral_id=COALESCE(?,ephemeral_id) WHERE delivery_key=?", status, message, ephemeral, key)
    }
    fun forgetDelivery(key: String) = database.write { c -> sqlUpdate(c, "DELETE FROM bot_deliveries WHERE delivery_key=?", key) }
    fun interruptedSends() = database.write { c -> sqlUpdate(c, "UPDATE bot_deliveries SET status='UNKNOWN' WHERE status='SENDING'") }
    /** Refresh existing live cards after an update, without creating new messages or changing training data. */
    fun refreshLiveCards() = database.write { c ->
        sqlUpdate(c, """UPDATE actions SET delivered_at=NULL WHERE id IN (
            SELECT MAX(a.id) FROM actions a JOIN trainings t ON t.group_id=a.group_id AND t.id=a.training_id
            JOIN bot_deliveries d ON d.delivery_key='training:' || t.group_id || ':' || t.id
            WHERE t.status IN ('OPEN','REVIEW') AND d.status='SENT' AND a.needs_delivery=1
            GROUP BY a.group_id,a.training_id)""")
    }
    fun pendingCards(): List<Triple<Long, String, Long>> = database.read { c ->
        sqlQuery(c, """SELECT group_id,training_id,MAX(id) FROM actions WHERE training_id IS NOT NULL
            AND needs_delivery=1 AND delivered_at IS NULL GROUP BY group_id,training_id ORDER BY MAX(id) LIMIT 20""") {
            Triple(it.getLong(1), it.getString(2), it.getLong(3))
        }
    }
    fun cardDelivered(group: Long, training: String, through: Long) = database.write { c ->
        sqlUpdate(c, "UPDATE actions SET delivered_at=? WHERE group_id=? AND training_id=? AND id<=? AND needs_delivery=1",
            clock.instant().toString(), group, training, through)
    }
}
