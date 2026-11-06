package ru.movereon.tennis.telegram

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Exercise the HTTP response classifier rather than supplying preclassified TelegramFailure instances. */
class PollStopTransportTest {
    private lateinit var server:HttpServer
    private lateinit var api:HttpTelegramApi
    private val response=AtomicReference(200 to """{"ok":true,"result":true}""")
    @BeforeEach fun setup() {
        server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val (status,body)=response.get()
            val bytes=body.toByteArray()
            exchange.sendResponseHeaders(status,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        api=HttpTelegramApi("123456:abcdefghijklmnopqrstuvwxyz_123456789",URI("http://127.0.0.1:${server.address.port}"))
    }
    @AfterEach fun stop() { server.stop(0) }
    private fun respond(code:Int,description:String,retryAfter:Int?=null) {
        response.set(code to buildJsonObject {
            put("ok",false);put("error_code",code);put("description",description)
            if(retryAfter!=null) put("parameters",buildJsonObject { put("retry_after",retryAfter) })
        }.toString())
    }

    @Test fun `stopPoll 400 identifies known missing message descriptions without treating any rejection as missing`() {
        for(description in listOf("Bad Request: message to stop poll not found","Bad Request: message to stopPoll not found","Bad Request: poll not found")) {
            respond(400,description)
            val failure=assertFailsWith<TelegramFailure> { api.stopPoll(-1,8) }
            assertEquals(FailureKind.MESSAGE_MISSING,failure.kind,description)
            assertEquals(400,failure.code)
        }
        respond(400,"Bad Request: not enough rights to stop this poll")
        assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { api.stopPoll(-1,8) }.kind)
        respond(403,"Forbidden: bot was kicked from the supergroup chat")
        assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { api.stopPoll(-1,8) }.kind)
    }

    @Test fun `stopPoll preserves authentication conflict and temporary error categories`() {
        for(code in listOf(401,409)) {
            respond(code,if(code==401) "Unauthorized" else "Conflict: terminated by another getUpdates request")
            val failure=assertFailsWith<TelegramFailure> { api.stopPoll(-1,8) }
            assertEquals(FailureKind.REJECTED,failure.kind)
            assertEquals(code,failure.code)
        }
        respond(429,"Too Many Requests: retry after 7",retryAfter=7)
        val throttled=assertFailsWith<TelegramFailure> { api.stopPoll(-1,8) }
        assertEquals(FailureKind.RETRY_LATER,throttled.kind);assertEquals(7,throttled.retryAfter)
        respond(503,"Service Unavailable")
        assertEquals(FailureKind.UNCERTAIN,assertFailsWith<TelegramFailure> { api.stopPoll(-1,8) }.kind)
    }

    @Test fun `an already closed poll is successful while a missing poll error on another method stays rejected`() {
        respond(400,"Bad Request: poll has already been closed")
        api.stopPoll(-1,8)
        respond(400,"Bad Request: poll not found")
        assertEquals(FailureKind.REJECTED,assertFailsWith<TelegramFailure> { api.editKeyboard(-1,8,TgKeyboard(emptyList())) }.kind)
    }
}
