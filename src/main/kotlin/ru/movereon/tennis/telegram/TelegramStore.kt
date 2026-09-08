package ru.movereon.tennis.telegram

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class TelegramStore(private val database: SqliteAccountingStore) {
    private val json = Json { encodeDefaults = true }
    fun bind(bot: TgUser) = database.writeTransaction { c ->
        val previous = query(c, "SELECT bot_id FROM tg_identity WHERE singleton=1") { it.getLong(1) }.singleOrNull()
        require(previous == null || previous == bot.id) { "База уже используется другим ботом" }
        update(c, "INSERT INTO tg_identity VALUES(1,?,?) ON CONFLICT(singleton) DO UPDATE SET username=excluded.username", bot.id, requireNotNull(bot.username))
        update(c, "UPDATE tg_deliveries SET status='UNKNOWN' WHERE status='SENDING'")
    }
    fun register(group: BotGroup) = database.writeTransaction { c ->
        update(c, "INSERT INTO tg_groups VALUES(?,?,?) ON CONFLICT(group_id) DO UPDATE SET chat_id=excluded.chat_id,title=excluded.title", group.id, group.chatId, group.title)
    }
    fun group(id: String): BotGroup? = database.readTransaction { c -> query(c, "SELECT * FROM tg_groups WHERE group_id=?", id, map = ::groupRow).singleOrNull() }
    fun groups(): List<BotGroup> = database.readTransaction { c -> query(c,"SELECT * FROM tg_groups ORDER BY group_id",map=::groupRow) }
    fun groupByChat(chatId: Long): BotGroup? = database.readTransaction { c -> query(c, "SELECT * FROM tg_groups WHERE chat_id=?", chatId, map = ::groupRow).singleOrNull() }
    fun migrate(oldChat: Long, newChat: Long) = database.writeTransaction { c ->
        update(c, "UPDATE tg_groups SET chat_id=? WHERE chat_id=?", newChat, oldChat)
        update(c, "UPDATE tg_deliveries SET chat_id=?,message_id=NULL,status='FAILED' WHERE chat_id=?", newChat, oldChat)
    }
    fun remember(user: TgUser) = database.writeTransaction { c ->
        update(c, "INSERT INTO tg_users VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET display_name=excluded.display_name", user.id, user.firstName.take(100))
    }
    fun name(userId: Long): String = database.readTransaction { c -> query(c, "SELECT display_name FROM tg_users WHERE user_id=?", userId) { it.getString(1) }.singleOrNull() ?: "Участник $userId" }
    fun session(userId: Long): BotSession = database.readTransaction { c ->
        query(c, "SELECT * FROM tg_sessions WHERE user_id=?", userId) {
            BotSession(userId, it.getString("group_id"), it.getString("panel_id")?.toLong(), it.getString("input_json")?.let { value -> json.decodeFromString<PendingInput>(value) })
        }.singleOrNull() ?: BotSession(userId, null, null, null)
    }
    fun saveSession(session: BotSession) = database.writeTransaction { c ->
        update(c, "INSERT INTO tg_sessions VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET group_id=excluded.group_id,panel_id=excluded.panel_id,input_json=excluded.input_json",
            session.userId, session.groupId, session.panelId, session.input?.let { json.encodeToString(it) })
    }
    fun action(userId: Long, groupId: String, action: BotAction): String = database.writeTransaction { c ->
        val token = token()
        update(c, "INSERT INTO tg_actions VALUES(?,?,?,?)", token, userId, groupId, json.encodeToString(action)); token
    }
    fun actions(userId: Long,groupId: String,actions: List<BotAction>): List<String> = database.writeTransaction { c ->
        actions.map { action -> token().also { update(c,"INSERT INTO tg_actions VALUES(?,?,?,?)",it,userId,groupId,json.encodeToString(action)) } }
    }
    fun editor(userId: Long,groupId: String,id: String): DraftEditor? = database.readTransaction { c ->
        query(c,"SELECT editor_json FROM tg_editors WHERE user_id=? AND group_id=? AND editor_id=?",userId,groupId,id) {
            json.decodeFromString<DraftEditor>(it.getString(1))
        }.singleOrNull()
    }
    fun editors(userId: Long,groupId: String): List<DraftEditor> = database.readTransaction { c ->
        query(c,"SELECT editor_json FROM tg_editors WHERE user_id=? AND group_id=? ORDER BY rowid DESC",userId,groupId) {
            json.decodeFromString<DraftEditor>(it.getString(1))
        }
    }
    fun openEditor(userId: Long,editor: DraftEditor): DraftEditor = database.writeTransaction { c ->
        update(c,"INSERT OR IGNORE INTO tg_editors VALUES(?,?,?,?,?)",editor.id,userId,editor.groupId,editor.draftId,json.encodeToString(editor))
        query(c,"SELECT editor_json FROM tg_editors WHERE user_id=? AND group_id=? AND draft_id=?",userId,editor.groupId,editor.draftId) {
            json.decodeFromString<DraftEditor>(it.getString(1))
        }.single()
    }
    fun edit(userId: Long,groupId: String,id: String,version: Long,updateId: Long,change: (DraftEditor)->DraftEditor): DraftEditor = database.writeTransaction { c ->
        val old = query(c,"SELECT editor_json FROM tg_editors WHERE user_id=? AND group_id=? AND editor_id=?",userId,groupId,id) {
            json.decodeFromString<DraftEditor>(it.getString(1))
        }.singleOrNull() ?: throw NoSuchElementException("Editor is closed")
        if(old.lastUpdate == updateId) return@writeTransaction old
        ru.movereon.tennis.core.checkAccounting(old.revision==version,ru.movereon.tennis.core.ErrorCode.STALE_VERSION,"Editor changed")
        val next = change(old).copy(revision=old.revision+1,lastUpdate=updateId)
        update(c,"UPDATE tg_editors SET editor_json=? WHERE editor_id=?",json.encodeToString(next),id)
        next
    }
    fun closeEditor(userId: Long,groupId: String,id: String) = database.writeTransaction { c ->
        update(c,"DELETE FROM tg_editors WHERE user_id=? AND group_id=? AND editor_id=?",userId,groupId,id)
    }
    fun action(token: String): SavedAction? = database.readTransaction { c -> query(c, "SELECT * FROM tg_actions WHERE token=?", token) {
        SavedAction(token, it.getLong("user_id"), it.getString("group_id"), json.decodeFromString(it.getString("action_json")))
    }.singleOrNull() }
    fun link(groupId: String, action: BotAction): String = database.writeTransaction { c ->
        val payload = json.encodeToString(action)
        val old = query(c, "SELECT token FROM tg_links WHERE group_id=? AND action_json=?", groupId, payload) { it.getString(1) }.singleOrNull()
        old ?: token().also { update(c, "INSERT INTO tg_links VALUES(?,?,?)", it, groupId, payload) }
    }
    fun link(token: String): SavedLink? = database.readTransaction { c -> query(c, "SELECT * FROM tg_links WHERE token=?", token) {
        SavedLink(token, it.getString("group_id"), json.decodeFromString(it.getString("action_json")))
    }.singleOrNull() }
    fun completed(updateId: Long): Boolean = database.readTransaction { c -> query(c, "SELECT completed FROM tg_updates WHERE update_id=?", updateId) { it.getInt(1) == 1 }.singleOrNull() ?: false }
    fun complete(updateId: Long) = database.writeTransaction { c -> update(c, "INSERT INTO tg_updates VALUES(?,1) ON CONFLICT(update_id) DO UPDATE SET completed=1", updateId) }
    fun offset(): Long? = database.readTransaction { c -> query(c, "SELECT MAX(update_id) FROM tg_updates WHERE completed=1") { it.getString(1)?.toLong()?.plus(1) }.single() }
    fun plan(updateId: Long): SavedPlan? = database.readTransaction { c -> query(c, "SELECT * FROM tg_plans WHERE update_id=?", updateId) {
        SavedPlan(it.getLong("user_id"), it.getString("group_id"), it.getString("token"), json.decodeFromString(it.getString("action_json")))
    }.singleOrNull() }
    fun planForToken(userId: Long,groupId: String,token: String): BotAction? = database.readTransaction { c ->
        query(c,"SELECT action_json FROM tg_plans WHERE user_id=? AND group_id=? AND token=? ORDER BY update_id LIMIT 1",userId,groupId,token) {
            json.decodeFromString<BotAction>(it.getString(1))
        }.singleOrNull()
    }
    fun savePlan(updateId: Long, plan: SavedPlan) = database.writeTransaction { c ->
        update(c, "INSERT OR IGNORE INTO tg_plans VALUES(?,?,?,?,?)", updateId, plan.userId, plan.groupId, plan.token, json.encodeToString(plan.action))
    }
    fun delivery(key: String): Delivery? = database.readTransaction { c -> query(c, "SELECT * FROM tg_deliveries WHERE delivery_key=?", key, map = ::deliveryRow).singleOrNull() }
    fun sending(key: String, groupId: String?, chatId: Long) = database.writeTransaction { c ->
        update(c, "INSERT INTO tg_deliveries VALUES(?,?,?,NULL,'SENDING') ON CONFLICT(delivery_key) DO UPDATE SET status='SENDING'", key, groupId, chatId)
    }
    fun deliveryStatus(key: String, status: String, messageId: Long? = null) = database.writeTransaction { c ->
        update(c, "UPDATE tg_deliveries SET status=?,message_id=COALESCE(?,message_id) WHERE delivery_key=?", status, messageId, key)
    }
    fun prepareRecovery(token: String, key: String) = database.writeTransaction { c ->
        val exists = query(c,"SELECT delivery_key FROM tg_recoveries WHERE token=?",token) { it.getString(1) }.singleOrNull()
        if(exists == null) {
            update(c,"INSERT INTO tg_recoveries VALUES(?,?)",token,key)
            update(c,"DELETE FROM tg_deliveries WHERE delivery_key=? AND status IN ('UNKNOWN','FAILED','BLOCKED')",key)
        } else require(exists == key)
    }
    fun troubled(groupId: String): List<Delivery> = database.readTransaction { c ->
        query(c, "SELECT * FROM tg_deliveries WHERE group_id=? AND chat_id < 0 AND status IN ('UNKNOWN','FAILED','BLOCKED')", groupId, map = ::deliveryRow)
    }
    fun initialized(groupId: String): Boolean = database.readTransaction { c -> query(c, "SELECT group_id FROM app_groups WHERE group_id=?", groupId) { it.getString(1) }.isNotEmpty() }
    private fun token() = UUID.randomUUID().toString().replace("-", "")
    private fun groupRow(row: ResultSet) = BotGroup(row.getString("group_id"), row.getLong("chat_id"), row.getString("title"))
    private fun deliveryRow(row: ResultSet) = Delivery(row.getString("delivery_key"), row.getString("group_id"), row.getLong("chat_id"), row.getString("message_id")?.toLong(), row.getString("status"))
    private fun update(c: Connection, sql: String, vararg args: Any?) = c.prepareStatement(sql).use { s -> args.forEachIndexed { i,v -> s.setObject(i+1,v) }; s.executeUpdate() }
    private fun <T> query(c: Connection, sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = c.prepareStatement(sql).use { s ->
        args.forEachIndexed { i,v -> s.setObject(i+1,v) }; s.executeQuery().use { rows -> buildList { while(rows.next()) add(map(rows)) } }
    }
}
