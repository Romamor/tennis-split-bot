package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.Account
import ru.movereon.tennis.application.SettlementService
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.storage.sqlQuery
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.sql.SQLException
import java.time.*
import kotlin.test.*

class UpdateInboxTest {
    @TempDir lateinit var directory:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-11-06T12:00:00Z")
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=fixed(now,zone)
        override fun instant()=now
    }
    private val clock=TestClock()
    private fun database()=Database(directory.resolve("inbox.sqlite"))
    private fun text(id:Long,user:Long)=TgUpdate(id,TgMessage(id,TgChat(user,"private"),TgUser(user),"message $id"))
    private fun complete(database:Database,id:Long)=InteractionStore(database,clock).complete(id)
    private fun sequence(database:Database)=database.read { c ->
        sqlQuery(c,"SELECT received_order FROM bot_polling WHERE singleton=1") { it.getLong(1) }.single()
    }

    @Test fun `durable receipt survives restart before planning and retries cannot pin the polling cursor`() {
        val db=database()
        var inbox=UpdateInbox(db,clock)
        assertNull(inbox.offset())
        val first=text(101,1)
        val second=text(102,2)
        inbox.accept(listOf(second,first))
        assertEquals(103L,inbox.offset())
        assertEquals(listOf(first,second),inbox.pending().map { it.update })
        // New identities are stored as input, without violating the users foreign key.
        assertEquals(0,db.read { c -> sqlQuery(c,"SELECT COUNT(*) FROM users") { it.getInt(1) }.single() })
        inbox.retry(101,60_000)
        inbox=UpdateInbox(Database(db.path),clock)
        assertEquals(listOf(102L),inbox.pending().map { it.id })
        assertEquals(103L,inbox.offset())
        assertEquals(0,inbox.nextDelay(25))
        complete(db,102)
        assertEquals(25,inbox.nextDelay(25))
        // A duplicate receipt neither moves the cursor backwards nor cancels a local delay.
        inbox.accept(listOf(first))
        assertEquals(103L,inbox.offset())
        assertEquals(2L,sequence(db))
        assertTrue(inbox.pending().isEmpty())
        clock.now=clock.now.plusMillis(59_999)
        assertEquals(1,inbox.nextDelay(25))
        clock.now=clock.now.plusMillis(1)
        assertEquals(0,inbox.nextDelay(25))
        assertEquals(first,inbox.pending().single().update)
    }

    @Test fun `one authors private messages callbacks and votes retain order while another author proceeds`() {
        val db=database()
        val inbox=UpdateInbox(db,clock)
        val callback=TgUpdate(2,callback=TgCallback("callback",TgUser(1),
            TgMessage(20,TgChat(-1,"supergroup"),TgUser(900,isBot=true)),"n:button"))
        val vote=TgUpdate(3,pollAnswer=TgPollAnswer("poll",TgUser(1),listOf(0)))
        inbox.accept(listOf(text(1,1),callback,vote,text(4,2)))
        assertEquals(listOf(1L,4L),inbox.pending().map { it.id })
        inbox.retry(1,10_000)
        assertEquals(listOf(4L),inbox.pending().map { it.id })
        complete(db,4)
        assertTrue(inbox.pending().isEmpty())
        assertEquals(10,inbox.nextDelay(25),"A later ready event cannot bypass its delayed author head")
        complete(db,1)
        assertEquals(callback,inbox.pending().single().update)
        complete(db,2)
        assertEquals(vote,inbox.pending().single().update)
    }

    @Test fun `events without authors do not share a blocking null queue`() {
        val db=database()
        val inbox=UpdateInbox(db,clock)
        inbox.accept(listOf(TgUpdate(1),TgUpdate(2),text(3,1)))
        inbox.retry(1,60_000)
        assertEquals(listOf(2L,3L),inbox.pending().map { it.id })
    }

    @Test fun `delayed first page cannot hide ready events belonging to later authors`() {
        val db=database()
        val inbox=UpdateInbox(db,clock)
        inbox.accept((1L..25L).map { text(it,it) })
        (1L..20L).forEach { inbox.retry(it,60_000) }
        assertEquals(listOf(21L,22L),inbox.pending(2).map { it.id })
        assertEquals(0,inbox.nextDelay(25))
    }

    @Test fun `legacy saved plan precedes new inputs of its author and can acquire its raw update`() {
        val db=database()
        SettlementService(db,clock).remember(Account(1,"Synthetic user"))
        val state=InteractionStore(db,clock)
        val plan=EventPlan(1,1,ScreenAction("settings",0))
        state.plan(900,plan)
        val inbox=UpdateInbox(db,clock)
        inbox.accept(listOf(text(901,1),text(902,2)))
        val pending=inbox.pending()
        assertEquals(listOf(900L,902L),pending.map { it.id })
        assertNull(pending.first().update)
        assertEquals(plan,pending.first().plan)
        inbox.retry(900,30_000)
        inbox.accept(listOf(text(900,1)))
        assertEquals(listOf(902L),inbox.pending().map { it.id })
        assertEquals(903L,inbox.offset())
        assertEquals(2L,sequence(db))
        assertEquals(plan,state.plan(900))
        clock.now=clock.now.plusSeconds(30)
        assertEquals(listOf(902L,900L),inbox.pending().map { it.id },"Previously ready work runs before a newly matured retry")
        assertEquals(text(900,1),inbox.pending().single { it.id==900L }.update)
        complete(db,900)
        assertEquals(listOf(901L,902L),inbox.pending().map { it.id })
    }

    @Test fun `a failed batch insert rolls back every received event and the acknowledgement cursor`() {
        val db=database()
        val inbox=UpdateInbox(db,clock)
        inbox.accept(listOf(text(10,1)))
        db.write { c -> c.createStatement().use { statement ->
            statement.execute("""CREATE TRIGGER reject_second_received_update BEFORE INSERT ON bot_events
                WHEN NEW.update_id=12 BEGIN SELECT RAISE(ABORT,'simulated receipt failure'); END""")
        } }
        assertFailsWith<SQLException> { inbox.accept(listOf(text(11,2),text(12,3))) }
        assertEquals(11L,inbox.offset())
        assertEquals(1L,sequence(db))
        assertEquals(listOf(10L),inbox.pending().map { it.id })
        db.write { c -> c.createStatement().use { it.execute("DROP TRIGGER reject_second_received_update") } }
        inbox.accept(listOf(text(11,2),text(12,3)))
        assertEquals(13L,inbox.offset())
        assertEquals(3L,sequence(db))
        assertEquals(listOf(10L,11L,12L),inbox.pending().map { it.id })
    }

    @Test fun `completed duplicate receipt cannot resurrect raw input or a finished event`() {
        val db=database()
        val inbox=UpdateInbox(db,clock)
        val update=text(1,1)
        inbox.accept(listOf(update))
        complete(db,1)
        inbox.accept(listOf(update))
        inbox.retry(1,60_000)
        assertTrue(inbox.pending().isEmpty())
        assertEquals(2L,inbox.offset())
        assertEquals(1L,sequence(db))
        assertEquals(25,inbox.nextDelay(25))
        db.read { c ->
            sqlQuery(c,"SELECT completed,update_json,plan_json,ordering_key,next_attempt_at FROM bot_events WHERE update_id=1") {
                assertTrue(it.getBoolean(1))
                assertNull(it.getString(2))
                assertNull(it.getString(3))
                assertNull(it.getString(4))
                assertEquals(0L,it.getLong(5))
            }
        }
    }

    @Test fun `pending votes include delayed positive and withdrawn answers until their local work completes`() {
        val db=database()
        val inbox=UpdateInbox(db,clock)
        inbox.accept(listOf(
            TgUpdate(1,pollAnswer=TgPollAnswer("first",TgUser(1),listOf(0))),
            TgUpdate(2,pollAnswer=TgPollAnswer("other",TgUser(2),listOf(3))),
            TgUpdate(3,pollAnswer=TgPollAnswer("first",TgUser(3),emptyList()))))
        inbox.retry(1,60_000)
        assertTrue(inbox.hasPendingVotes("first"))
        assertTrue(inbox.hasPendingVotes("other"))
        assertFalse(inbox.hasPendingVotes("unrelated"))
        complete(db,3)
        assertTrue(inbox.hasPendingVotes("first"),"Delayed answers must prevent premature poll finalization")
        complete(db,1)
        assertFalse(inbox.hasPendingVotes("first"))
        assertTrue(inbox.hasPendingVotes("other"))
        complete(db,2)
        assertFalse(inbox.hasPendingVotes("other"))
    }
}
