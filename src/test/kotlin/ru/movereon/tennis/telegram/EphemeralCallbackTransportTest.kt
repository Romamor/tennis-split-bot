package ru.movereon.tennis.telegram

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class EphemeralCallbackTransportTest {
    private lateinit var server:HttpServer
    private lateinit var api:HttpTelegramApi
    private val bodies=CopyOnWriteArrayList<Pair<String,JsonObject>>()
    private val keyboard=TgKeyboard(listOf(listOf(TgButton("Закрыть",callbackData="n:test"))))
    private val richResponse="""{"ok":true,"result":{"chat":{"id":-123,"type":"supergroup"},"receiver_user":{"id":22},"ephemeral_message_id":73,"rich_message":{"blocks":[{"type":"paragraph","text":{"text":"Панель"}}]}}}"""
    private var response:(String)->Pair<Int,String> = { 200 to richResponse }
    @BeforeEach fun setup() {
        server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val method=exchange.requestURI.path.substringAfterLast('/')
            val raw=exchange.requestBody.bufferedReader().use { it.readText() }
            bodies+=method to Json.parseToJsonElement(raw).jsonObject
            val (status,result)=response(method)
            runCatching {
                val bytes=result.toByteArray()
                exchange.sendResponseHeaders(status,bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        api=HttpTelegramApi("123456:abcdefghijklmnopqrstuvwxyz_123456789",URI("http://127.0.0.1:${server.address.port}"))
    }
    @AfterEach fun stop() { server.stop(0) }
    private fun send()=api.ephemeralRich(-123,22,"callback","Панель","<p>Панель</p>",keyboard)
    private fun admin()=api.ephemeralRichAsAdmin(-123,22,"Панель","<p>Панель</p>",keyboard)
    private fun reject(code:Int,description:String) { response={ code to buildJsonObject {
        put("ok",false);put("error_code",code);put("description",description)
    }.toString() } }

    @Test fun `rich only replies are accepted and administrator sends omit callback credentials`() {
        val sent=api.callbackWindow(1000) { send() }
        assertNull(sent.text)
        assertEquals(73L,sent.ephemeralId)
        assertEquals(22L,sent.receiver!!.id)
        val callback=bodies.last().second.getValue("ephemeral_message_parameters").jsonObject
        assertEquals("callback",callback.getValue("callback_query_id").jsonPrimitive.content)
        assertFalse(callback.getValue("replace_callback_query_message").jsonPrimitive.boolean)
        api.callbackWindow(0) { admin() }
        api.ephemeralAsAdmin(-123,22,"Панель",keyboard)
        assertEquals(listOf("sendRichMessage","sendRichMessage","sendMessage"),bodies.map { it.first })
        for((_,body) in bodies.drop(1)) {
            val parameters=body.getValue("ephemeral_message_parameters").jsonObject
            assertEquals(22L,parameters.getValue("receiver_user_id").jsonPrimitive.long)
            assertFalse(parameters.containsKey("callback_query_id"))
            assertFalse(parameters.getValue("replace_callback_query_message").jsonPrimitive.boolean)
        }
    }

    @Test fun `expired callback budget sends no request and nested windows cannot extend it`() {
        val failure=assertFailsWith<TelegramFailure> { api.callbackWindow(0) { api.callbackWindow(1000) { send() } } }
        assertEquals(FailureKind.CALLBACK_EXPIRED,failure.kind)
        assertNull(failure.code)
        assertTrue(bodies.isEmpty())
        assertEquals(73L,send().ephemeralId,"The prior deadline must be restored after rejection")
    }

    @Test fun `one callback deadline includes preceding checks but does not cancel edits or membership requests`() {
        response={ method -> when(method) {
            "getChatMember" -> { Thread.sleep(30);200 to """{"ok":true,"result":{"status":"member"}}""" }
            else -> 200 to """{"ok":true,"result":true}"""
        } }
        val failure=assertFailsWith<TelegramFailure> {
            api.callbackWindow(1) {
                assertTrue(api.member(-123,22).present)
                api.editEphemeralRich(-123,22,73,"Панель","<p>Панель</p>",keyboard)
                send()
            }
        }
        assertEquals(FailureKind.CALLBACK_EXPIRED,failure.kind)
        assertEquals(listOf("getChatMember","editEphemeralMessageText"),bodies.map { it.first })
    }

    @Test fun `only an explicit callback rejection on a new personal send permits administrator recovery`() {
        for(description in listOf("Bad Request: query is too old and response timeout expired or query ID is invalid",
            "Bad Request: QUERY_ID_INVALID","Bad Request: callback_query_id is invalid","Bad Request: callback query expired")) {
            reject(400,description)
            assertEquals(FailureKind.CALLBACK_EXPIRED,assertFailsWith<TelegramFailure> { send() }.kind)
            assertEquals(FailureKind.CALLBACK_EXPIRED,assertFailsWith<TelegramFailure> { api.ephemeral(-123,22,"callback","Панель",keyboard) }.kind)
            assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { admin() }.kind)
            assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { api.answer("callback") }.kind)
            assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { api.sendRich(-123,"Панель","<p>Панель</p>",keyboard) }.kind)
        }
        reject(400,"Bad Request: not enough rights to send text messages to the chat")
        assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { send() }.kind)
        reject(503,"callback query expired")
        assertEquals(FailureKind.UNCERTAIN,assertFailsWith<TelegramFailure> { send() }.kind)
    }

    @Test fun `a callback send timing out after dispatch remains uncertain`() {
        // Establish the local connection before imposing a short response deadline.
        response={ 200 to """{"ok":true,"result":{"status":"member"}}""" }
        api.member(-123,22)
        response={ Thread.sleep(250);200 to richResponse }
        val failure=assertFailsWith<TelegramFailure> { api.callbackWindow(80) { send() } }
        assertEquals(FailureKind.UNCERTAIN,failure.kind,"A response timeout does not establish that Telegram rejected the send")
        assertFalse(failure.backgroundDeferred)
        assertEquals(1,bodies.count { it.first=="sendRichMessage" })
    }

    @Test fun `an administrator response without the expected personal audience stays uncertain`() {
        response={ 200 to """{"ok":true,"result":{"chat":{"id":-123,"type":"supergroup"},"rich_message":{"blocks":[]}}}""" }
        assertEquals(FailureKind.UNCERTAIN,assertFailsWith<TelegramFailure> { admin() }.kind)
    }
}
