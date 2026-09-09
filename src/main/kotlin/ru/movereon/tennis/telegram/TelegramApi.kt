package ru.movereon.tennis.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable data class TgUser(val id: Long, @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String = "", val username: String? = null,
    @SerialName("last_name") val lastName: String = "")
@Serializable data class TgChat(val id: Long, val type: String, val title: String? = null)
@Serializable data class TgButton(val text: String, @SerialName("callback_data") val callbackData: String? = null, val url: String? = null)
@Serializable data class TgKeyboard(@SerialName("inline_keyboard") val rows: List<List<TgButton>>)
@Serializable data class TgMessage(@SerialName("message_id") val id: Long = 0, val chat: TgChat, val from: TgUser? = null,
    val text: String? = null, @SerialName("reply_to_message") val replyTo: TgMessage? = null,
    @SerialName("reply_markup") val keyboard: TgKeyboard? = null, @SerialName("migrate_to_chat_id") val migrateTo: Long? = null,
    @SerialName("receiver_user") val receiver: TgUser? = null,
    @SerialName("ephemeral_message_id") val ephemeralId: Long? = null,
    @SerialName("new_chat_members") val newMembers: List<TgUser> = emptyList(),
    @SerialName("left_chat_member") val leftMember: TgUser? = null,
    @SerialName("users_shared") val usersShared: TgUsersShared? = null)
@Serializable data class TgCallback(val id: String, val from: TgUser, val message: TgMessage? = null, val data: String? = null)
@Serializable data class TgUpdate(@SerialName("update_id") val id: Long, val message: TgMessage? = null,
    @SerialName("callback_query") val callback: TgCallback? = null,
    @SerialName("chat_member") val memberUpdate: TgMemberUpdate? = null,
    @SerialName("my_chat_member") val botMemberUpdate: TgMemberUpdate? = null)
@Serializable data class TgSharedUser(@SerialName("user_id") val id: Long,
    @SerialName("first_name") val firstName: String = "", @SerialName("last_name") val lastName: String = "", val username: String? = null)
@Serializable data class TgUsersShared(@SerialName("request_id") val requestId: Int, val users: List<TgSharedUser>)
@Serializable data class TgMemberUpdate(val chat: TgChat, val from: TgUser, @SerialName("new_chat_member") val member: TgMember)
@Serializable data class TgMember(val status: String, @SerialName("is_member") val isMember: Boolean? = null, val user: TgUser? = null) {
    val present: Boolean get() = status in setOf("creator", "administrator", "member") || status == "restricted" && isMember == true
    val admin: Boolean get() = status in setOf("creator", "administrator")
    val absent: Boolean get() = status in setOf("left", "kicked")
}

enum class FailureKind { REJECTED, RETRY_LATER, UNCERTAIN, NOT_MODIFIED, MESSAGE_MISSING }
class TelegramFailure(val kind: FailureKind, val code: Int? = null, val retryAfter: Int? = null) :
    IOException("Telegram request failed: $kind${code?.let { " ($it)" } ?: ""}")

interface TelegramApi {
    fun me(): TgUser
    fun updates(offset: Long?, timeout: Int): List<TgUpdate>
    fun member(chatId: Long, userId: Long): TgMember
    fun send(chatId: Long, text: String, keyboard: TgKeyboard? = null, forceReply: Boolean = false): TgMessage
    fun edit(chatId: Long, messageId: Long, text: String, keyboard: TgKeyboard? = null)
    fun answer(callbackId: String, text: String? = null, alert: Boolean = false)
    fun administrators(chatId: Long): List<TgMember> = emptyList()
    fun ephemeral(chatId: Long, userId: Long, callbackId: String, text: String, keyboard: TgKeyboard): TgMessage =
        throw TelegramFailure(FailureKind.REJECTED)
    fun editEphemeral(chatId: Long, userId: Long, ephemeralId: Long, text: String, keyboard: TgKeyboard) {
        throw TelegramFailure(FailureKind.REJECTED)
    }
    fun requestUsers(chatId: Long, text: String, requestId: Int): TgMessage = throw TelegramFailure(FailureKind.REJECTED)
    fun deleteEphemeral(chatId: Long, userId: Long, ephemeralId: Long) { throw TelegramFailure(FailureKind.REJECTED) }
}

/** No redirects, URL logging or upstream exception causes: the request URL contains the token. */
class HttpTelegramApi(private val token: String, private val endpoint: URI = URI("https://api.telegram.org")) : TelegramApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    init {
        require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]{20,}"))) { "Некорректный формат токена бота" }
        require(endpoint.scheme == "https" || endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "localhost", "::1"))
    }
    override fun me(): TgUser = json.decodeFromJsonElement(call("getMe", buildJsonObject {}))
    override fun updates(offset: Long?, timeout: Int): List<TgUpdate> = json.decodeFromJsonElement(call("getUpdates", buildJsonObject {
        offset?.let { put("offset", it) }; put("timeout", timeout)
        put("allowed_updates", buildJsonArray { add("message"); add("callback_query"); add("chat_member"); add("my_chat_member") })
    }, timeout + 20))
    override fun member(chatId: Long, userId: Long): TgMember = json.decodeFromJsonElement(call("getChatMember", buildJsonObject {
        put("chat_id", chatId); put("user_id", userId)
    }))
    override fun send(chatId: Long, text: String, keyboard: TgKeyboard?, forceReply: Boolean): TgMessage =
        json.decodeFromJsonElement(call("sendMessage", buildJsonObject {
            put("chat_id", chatId); put("text", text); put("link_preview_options", buildJsonObject { put("is_disabled", true) })
            if (keyboard != null) put("reply_markup", json.encodeToJsonElement(keyboard))
            else if (forceReply) put("reply_markup", buildJsonObject { put("force_reply", true); put("selective", true) })
        }))
    override fun edit(chatId: Long, messageId: Long, text: String, keyboard: TgKeyboard?) {
        try {
            call("editMessageText", buildJsonObject {
                put("chat_id", chatId); put("message_id", messageId); put("text", text)
                put("link_preview_options", buildJsonObject { put("is_disabled", true) })
                put("reply_markup", json.encodeToJsonElement(keyboard ?: TgKeyboard(emptyList())))
            })
        } catch (failure: TelegramFailure) { if (failure.kind != FailureKind.NOT_MODIFIED) throw failure }
    }
    override fun answer(callbackId: String, text: String?, alert: Boolean) {
        call("answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId); text?.let { put("text", it.take(180)) }; put("show_alert", alert) })
    }

    override fun administrators(chatId: Long): List<TgMember> = json.decodeFromJsonElement(call("getChatAdministrators", buildJsonObject { put("chat_id", chatId) }))
    override fun ephemeral(chatId: Long, userId: Long, callbackId: String, text: String, keyboard: TgKeyboard): TgMessage =
        json.decodeFromJsonElement<TgMessage>(call("sendMessage", buildJsonObject {
            put("chat_id", chatId); put("text", text)
            put("ephemeral_message_parameters", buildJsonObject {
                put("receiver_user_id", userId); put("callback_query_id", callbackId)
                // Keep personal controls separate from the shared card, which is updated after every change.
                put("replace_callback_query_message", false)
            })
            put("reply_markup", json.encodeToJsonElement(keyboard))
        })).also { if (it.receiver?.id != userId || it.ephemeralId == null) throw TelegramFailure(FailureKind.UNCERTAIN) }
    override fun editEphemeral(chatId: Long, userId: Long, ephemeralId: Long, text: String, keyboard: TgKeyboard) {
        try {
            call("editEphemeralMessageText", buildJsonObject {
                put("chat_id", chatId); put("receiver_user_id", userId); put("ephemeral_message_id", ephemeralId)
                put("text", text); put("reply_markup", json.encodeToJsonElement(keyboard))
            })
        } catch (failure: TelegramFailure) { if (failure.kind != FailureKind.NOT_MODIFIED) throw failure }
    }
    override fun requestUsers(chatId: Long, text: String, requestId: Int): TgMessage =
        json.decodeFromJsonElement(call("sendMessage", buildJsonObject {
            put("chat_id", chatId); put("text", text)
            put("reply_markup", buildJsonObject {
                put("resize_keyboard", true); put("one_time_keyboard", true)
                put("keyboard", buildJsonArray { add(buildJsonArray { add(buildJsonObject {
                    put("text", "Выбрать аккаунт Telegram")
                    put("request_users", buildJsonObject {
                        put("request_id", requestId); put("user_is_bot", false); put("max_quantity", 1)
                        put("request_name", true); put("request_username", true)
                    })
                }) }) })
            })
        }))

    override fun deleteEphemeral(chatId: Long, userId: Long, ephemeralId: Long) {
        call("deleteEphemeralMessage", buildJsonObject {
            put("chat_id", chatId); put("receiver_user_id", userId); put("ephemeral_message_id", ephemeralId)
        })
    }

    private fun call(method: String, body: JsonObject, timeout: Int = 25): JsonElement {
        val response = try {
            val request = HttpRequest.newBuilder(URI("${endpoint.toString().trimEnd('/')}/bot$token/$method"))
                .timeout(Duration.ofSeconds(timeout.toLong())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: IOException) { throw TelegramFailure(FailureKind.UNCERTAIN) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw TelegramFailure(FailureKind.UNCERTAIN) }
        val envelope = try { json.parseToJsonElement(response.body()).jsonObject } catch (_: Exception) { throw TelegramFailure(FailureKind.UNCERTAIN) }
        if (envelope["ok"]?.jsonPrimitive?.booleanOrNull == true) return envelope["result"] ?: throw TelegramFailure(FailureKind.UNCERTAIN)
        val code = envelope["error_code"]?.jsonPrimitive?.intOrNull ?: response.statusCode()
        val description = envelope["description"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val kind = when {
            code == 429 -> FailureKind.RETRY_LATER
            code >= 500 -> FailureKind.UNCERTAIN
            "message is not modified" in description -> FailureKind.NOT_MODIFIED
            "message to edit not found" in description -> FailureKind.MESSAGE_MISSING
            else -> FailureKind.REJECTED
        }
        throw TelegramFailure(kind, code, envelope["parameters"]?.jsonObject?.get("retry_after")?.jsonPrimitive?.intOrNull)
    }
}
