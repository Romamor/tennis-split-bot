package ru.movereon.tennis.telegram

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.*

class BackgroundTimeoutRecoveryTest {
    @Test fun `timed out background send stays uncertain and yields without a generic sleep`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            Thread.sleep(200)
            exchange.close()
        }
        server.start()
        try {
            val api=HttpTelegramApi("123456:abcdefghijklmnopqrstuvwxyz_123456789",
                URI("http://127.0.0.1:${server.address.port}"),backgroundBudgetMillis=40)
            val failure=assertFailsWith<TelegramFailure> {
                api.background { api.send(-1,"Карточка") }
            }
            assertEquals(FailureKind.UNCERTAIN,failure.kind,"A timed-out send may already have reached Telegram")
            assertTrue(failure.backgroundDeferred,"The runtime must yield rather than adding its normal three-second pause")
        } finally { server.stop(0) }
    }
}
