package ru.movereon.tennis.telegram

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.*
import kotlin.test.*

class UiMetricsTest {
    @TempDir lateinit var directory:Path
    private class Time:Clock() {
        var now=1_000_000L
        var nanos=0L
        fun advance(ms:Long) { now+=ms;nanos+=ms*1_000_000 }
        override fun instant():Instant=Instant.ofEpochMilli(now)
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
    }
    @Test fun `response includes queue and retry delay while API excludes long polling`() {
        val clock=Time();val metrics=UiMetrics(true,clock) { clock.nanos }
        val received=clock.now
        clock.advance(500)
        assertFailsWith<TelegramFailure> { metrics.interaction(77,received) {
            metrics.action("participation_time")
            metrics.api("getChatMember") { clock.advance(250);throw TelegramFailure(FailureKind.RETRY_LATER,429) }
        } }
        clock.advance(3000)
        metrics.interaction(77,received) {
            metrics.api("getUpdates") { clock.advance(1000) }
            metrics.api("editEphemeralMessageText") { clock.advance(250) }
            metrics.responded()
        }
        val text=metrics.exposition()
        assertContains(text,"tennis_ui_response_seconds_sum{category=\"participation\"} 5.0")
        assertContains(text,"tennis_ui_response_seconds_count{category=\"participation\"} 1")
        assertContains(text,"tennis_ui_queue_seconds_count{category=\"all\"} 1")
        assertContains(text,"tennis_ui_attempts_total{outcome=\"retry\",category=\"participation\"} 1")
        assertContains(text,"tennis_ui_api_seconds_sum{method=\"getChatMember\"} 0.25")
        assertFalse(text.contains("getUpdates"))
    }
    @Test fun `uncertain and rejected panels never contribute successful response latency`() {
        val metrics=UiMetrics(true)
        for(kind in listOf(FailureKind.UNCERTAIN,FailureKind.REJECTED)) metrics.interaction(kind.ordinal.toLong(),null) {
            metrics.failure(kind);metrics.responded()
        }
        assertFalse(metrics.exposition().lineSequence().any { it.startsWith("tennis_ui_response_seconds_count") && !it.endsWith(" 0") })
        assertContains(metrics.exposition(),"outcome=\"uncertain\"")
        assertContains(metrics.exposition(),"outcome=\"rejected\"")
    }
    @Test fun `accepted response is counted once even if later work retries`() {
        val metrics=UiMetrics(true)
        assertFailsWith<TelegramFailure> { metrics.interaction(1,null) {
            metrics.responded();throw TelegramFailure(FailureKind.RETRY_LATER)
        } }
        metrics.interaction(1,null) { metrics.responded() }
        assertContains(metrics.exposition(),"tennis_ui_response_seconds_count{category=\"other\"} 1")
    }
    @Test fun `restart recovers receipt time without persisting metrics in database`() {
        val clock=Time();val received=clock.now-12_000
        val metrics=UiMetrics(true,clock) { clock.nanos }
        metrics.interaction(123,received) { metrics.action("training");metrics.responded() }
        assertContains(metrics.exposition(),"tennis_ui_response_seconds_sum{category=\"training\"} 12.0")
    }
    @Test fun `labels and retained retries stay bounded even with arbitrary input`() {
        val metrics=UiMetrics(true)
        repeat(600) { id -> assertFailsWith<TelegramFailure> { metrics.interaction(id.toLong(),null) {
            metrics.action("secret-name-$id")
            metrics.api("secret-method-$id") { Unit }
            throw TelegramFailure(FailureKind.RETRY_LATER)
        } } }
        val text=metrics.exposition()
        assertFalse(text.contains("secret"))
        assertContains(text,"tennis_ui_attempts_total{outcome=\"evicted\",category=\"other\"} 88")
        assertTrue(text.length<64_000)
    }
    @Test fun `atomic publication is throttled and a broken destination cannot break processing`() {
        val clock=Time();val metrics=UiMetrics(true,clock) { clock.nanos }
        val target=directory.resolve("metrics.prom")
        metrics.interaction(1,null) { metrics.responded() }
        assertTrue(metrics.publish(target))
        assertContains(Files.readString(target),"tennis_ui_response_seconds_count")
        var writes=0
        assertTrue(metrics.publish(target) { _,_->writes++ })
        assertEquals(0,writes)
        clock.advance(30_000)
        assertFalse(metrics.publish(target) { _,_->writes++;error("disk unavailable") })
        assertTrue(metrics.publish(target) { _,_->writes++ })
        assertEquals(1,writes)
        assertEquals(listOf(target),Files.list(directory).use { it.toList() })
    }
    @Test fun `disabled metrics never run clocks or write files and preserve exceptions`() {
        val metrics=UiMetrics(false,nanoTime={ error("clock must not run") })
        metrics.interaction(1,null) { metrics.api("sendMessage") { metrics.responded() } }
        assertTrue(metrics.publish(directory.resolve("absent")) { _,_->error("no write") })
        assertFalse(Files.exists(directory.resolve("absent")))
    }
}
