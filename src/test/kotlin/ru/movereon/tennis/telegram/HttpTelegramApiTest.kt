package ru.movereon.tennis.telegram

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HttpTelegramApiTest {
    @TempDir lateinit var directory: Path
    private lateinit var server: HttpServer
    private lateinit var api: HttpTelegramApi
    private val token="123456:abcdefghijklmnopqrstuvwxyz_123456789"
    private val bodies=mutableListOf<Pair<String,JsonObject>>()
    private var response: (String)->Pair<Int,String> = { 200 to "{\"ok\":true,\"result\":true}" }
    @BeforeEach fun setup() {
        server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val method=exchange.requestURI.path.substringAfterLast('/')
            val body=exchange.requestBody.bufferedReader().use { it.readText() }
            bodies += method to Json.parseToJsonElement(body).jsonObject
            val (status,result)=response(method)
            val bytes=result.toByteArray()
            exchange.sendResponseHeaders(status,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        api=HttpTelegramApi(token,URI("http://127.0.0.1:${server.address.port}"))
    }
    @AfterEach fun stop() { server.stop(0) }

    @Test fun `rich cards and ephemeral tables use Telegram rich message API and pin requests notify members`() {
        response={ method -> 200 to if(method=="sendRichMessage")
            """{"ok":true,"result":{"message_id":77,"chat":{"id":-123,"type":"supergroup"},"receiver_user":{"id":22},"ephemeral_message_id":73}}"""
            else """{"ok":true,"result":true}"""
        }
        val keyboard=TgKeyboard(listOf(listOf(TgButton("Открыть",callbackData="n:test"))))
        val html="<table><tr><td><a href=\"tg://user?id=22\">Игрок</a></td><td>0 ч</td></tr></table>"
        api.sendRich(-123,"Игрок",html,keyboard)
        assertEquals("sendRichMessage",bodies.last().first)
        assertTrue(bodies.last().second.getValue("disable_notification").jsonPrimitive.boolean)
        assertFalse(bodies.last().second.containsKey("text"))
        api.editRich(-123,77,"Игрок",html,keyboard)
        assertEquals(html,bodies.last().second.getValue("rich_message").jsonObject.getValue("html").jsonPrimitive.content)
        api.ephemeralRich(-123,22,"callback","Игрок",html,keyboard)
        assertFalse(bodies.last().second.getValue("ephemeral_message_parameters").jsonObject.getValue("replace_callback_query_message").jsonPrimitive.boolean)
        api.editEphemeralRich(-123,22,73,"Игрок",html,keyboard)
        assertEquals("editEphemeralMessageText",bodies.last().first)
        assertEquals(html,bodies.last().second.getValue("rich_message").jsonObject.getValue("html").jsonPrimitive.content)
        api.pin(-123,77)
        assertEquals("pinChatMessage",bodies.last().first)
        assertFalse(bodies.last().second.getValue("disable_notification").jsonPrimitive.boolean)
        response={ 200 to """{"ok":true,"result":{"message_id":9,"chat":{"id":-123,"type":"supergroup"}}}""" }
        assertEquals(FailureKind.UNCERTAIN,assertFailsWith<TelegramFailure> { api.ephemeralRich(-123,22,"cb","x",html,keyboard) }.kind)
    }

    @Test fun `API requests preserve integer IDs and encode Telegram fields correctly`() {
        response={ method -> 200 to when(method) {
            "getMe" -> """{"ok":true,"result":{"id":123456,"is_bot":true,"first_name":"Бот","username":"test_bot","new_field":true}}"""
            "getChatMember" -> """{"ok":true,"result":{"status":"restricted","is_member":true,"user":{"id":22}}}"""
            else -> """{"ok":true,"result":{"message_id":77,"chat":{"id":4503599627370000,"type":"private"},"text":"ok"}}"""
        } }
        assertEquals("test_bot",api.me().username)
        assertTrue(api.member(-1009999999999,22).present)
        val keyboard=TgKeyboard(listOf(listOf(TgButton("Кнопка",callbackData="opaque"))))
        val sent=api.send(4503599627370000,"Привет",keyboard)
        assertEquals(77L,sent.id)
        val body=bodies.last().second
        assertEquals(4503599627370000L,body.getValue("chat_id").jsonPrimitive.long)
        assertEquals("opaque",body.getValue("reply_markup").jsonObject.getValue("inline_keyboard").jsonArray[0].jsonArray[0].jsonObject.getValue("callback_data").jsonPrimitive.content)
        assertFalse(body.containsKey("parse_mode"))
    }

    @Test fun `polling sends persisted offset and filters supported update types`() {
        response={ 200 to """{"ok":true,"result":[{"update_id":42,"message":{"message_id":1,"chat":{"id":9,"type":"private"},"from":{"id":9,"first_name":"Имя"},"text":"/start"}}]}""" }
        val result=api.updates(41,25)
        assertEquals(42L,result.single().id)
        val body=bodies.single().second
        assertEquals(41L,body.getValue("offset").jsonPrimitive.long)
        assertEquals(listOf("message","callback_query","chat_member","my_chat_member"),body.getValue("allowed_updates").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `force reply is sent only as a private input mechanism`() {
        response={ 200 to """{"ok":true,"result":{"message_id":1,"chat":{"id":1,"type":"private"}}}""" }
        api.send(1,"Введи сумму",forceReply=true)
        assertTrue(bodies.single().second.getValue("reply_markup").jsonObject.getValue("force_reply").jsonPrimitive.boolean)
    }

    @Test fun `not modified edits are successful and rate limits expose retry delay`() {
        response={ 400 to """{"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}""" }
        api.edit(1,2,"Текст")
        response={ 429 to """{"ok":false,"error_code":429,"description":"Too many requests","parameters":{"retry_after":7}}""" }
        val failure=assertFailsWith<TelegramFailure> { api.send(1,"Текст") }
        assertEquals(FailureKind.RETRY_LATER,failure.kind)
        assertEquals(7,failure.retryAfter)
    }

    @Test fun `errors never disclose the token or the request URI`() {
        response={ 401 to """{"ok":false,"error_code":401,"description":"Unauthorized token $token"}""" }
        val failure=assertFailsWith<TelegramFailure> { api.me() }
        assertFalse(failure.stackTraceToString().contains(token))
        assertFalse(failure.stackTraceToString().contains("/bot$token"))
        response={ 500 to "not JSON" }
        assertEquals(FailureKind.UNCERTAIN,assertFailsWith<TelegramFailure> { api.send(1,"Текст") }.kind)
    }

    @Test fun `configuration needs a token and never evaluates shell expressions`() {
        val env=directory.resolve("config.env")
        Files.writeString(env,"TELEGRAM_BOT_TOKEN=\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { BotConfig.load(env,emptyMap()) }.message!!.contains("@BotFather"))
        Files.writeString(env,"TELEGRAM_BOT_TOKEN='$token'\nBOT_DATABASE_PATH=\$(touch never-execute)\nBOT_TIME_ZONE=Europe/Moscow\n")
        val config=BotConfig.load(env,emptyMap())
        assertEquals(token,config.token)
        assertEquals("\$(touch never-execute)",config.database.toString())
        assertFalse(config.toString().contains(token))
        val other="999999:abcdefghijklmnopqrstuvwxyz_987654321"
        assertEquals(other,BotConfig.load(env,mapOf("TELEGRAM_BOT_TOKEN" to other)).token)
    }

    @Test fun `ephemeral messages use recipient parameters and verify the returned audience`() {
        response = { method -> 200 to if (method == "sendMessage")
            """{"ok":true,"result":{"chat":{"id":-100123,"type":"supergroup"},"receiver_user":{"id":4503599627370000},"ephemeral_message_id":73}}"""
            else """{"ok":true,"result":true}"""
        }
        val keyboard = TgKeyboard(listOf(listOf(TgButton("+50 ₽", callbackData = "n:token"))))
        val msg = api.ephemeral(-100123,4503599627370000,"callback", "Мои данные", keyboard)
        assertEquals(73L, msg.ephemeralId)
        val parameters = bodies.last().second.getValue("ephemeral_message_parameters").jsonObject
        assertEquals(4503599627370000L, parameters.getValue("receiver_user_id").jsonPrimitive.long)
        assertFalse(parameters.getValue("replace_callback_query_message").jsonPrimitive.boolean,
            "The shared card is edited after joining and must not host the personal panel")
        api.editEphemeral(-100123,4503599627370000,73,"Обновлено",keyboard)
        assertEquals("editEphemeralMessageText", bodies.last().first)
        assertEquals(73L,bodies.last().second.getValue("ephemeral_message_id").jsonPrimitive.long)
        api.deleteEphemeral(-100123,4503599627370000,73)
        assertEquals("deleteEphemeralMessage",bodies.last().first)
        assertEquals(4503599627370000L,bodies.last().second.getValue("receiver_user_id").jsonPrimitive.long)
        assertEquals(73L,bodies.last().second.getValue("ephemeral_message_id").jsonPrimitive.long)
        response = { 200 to """{"ok":true,"result":{"message_id":9,"chat":{"id":-100123,"type":"supergroup"}}}""" }
        assertEquals(FailureKind.UNCERTAIN, assertFailsWith<TelegramFailure> {
            api.ephemeral(-100123,4503599627370000,"callback", "Мои данные",keyboard)
        }.kind)
    }

    @Test fun `account picker asks for a real user and parses member updates`() {
        response = { method -> 200 to if (method == "sendMessage")
            """{"ok":true,"result":{"message_id":1,"chat":{"id":1,"type":"private"}}}"""
            else """{"ok":true,"result":[{"update_id":51,"chat_member":{"chat":{"id":-100,"type":"supergroup"},"from":{"id":1},"new_chat_member":{"status":"member","user":{"id":22,"first_name":"Игрок","last_name":"Фамилия"}}}}]}"""
        }
        api.requestUsers(1,"Выбери аккаунт",45)
        val request = bodies.last().second.getValue("reply_markup").jsonObject.getValue("keyboard").jsonArray[0].jsonArray[0].jsonObject.getValue("request_users").jsonObject
        assertFalse(request.getValue("user_is_bot").jsonPrimitive.boolean)
        assertEquals(45, request.getValue("request_id").jsonPrimitive.int)
        assertEquals("Фамилия", api.updates(null,1).single().memberUpdate!!.member.user!!.lastName)
    }
}
