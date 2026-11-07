package ru.movereon.tennis.telegram

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.selfservice.UpdateInbox
import ru.movereon.tennis.storage.Database
import java.nio.file.Path
import kotlin.test.*

class BotPollingBackoffTest {
    @TempDir lateinit var directory:Path

    @Test fun `persisted polling deadline survives restart and clears only after a real response`() {
        var now=1_800_000_000_000L
        val path=directory.resolve("polling-restart.sqlite")
        fun schedule():UpdatePollingSchedule {
            val inbox=UpdateInbox(Database(path))
            return UpdatePollingSchedule(loadDeadline={ inbox.pollRetryAt() },saveDeadline={ inbox.pollRetryAt(it) },nowMillis={ now })
        }
        var polling=schedule()
        assertNull(polling.receive { throw TelegramFailure(FailureKind.RETRY_LATER,429,3600) })
        val deadline=now+3_600_000
        assertEquals(deadline,UpdateInbox(Database(path)).pollRetryAt())
        now=deadline-1
        polling=schedule()
        var localPasses=0
        runQueuedBotCycle({ polling.receive { fail("Restart must not shorten retry_after") } },
            { fail("No Telegram response was received") },{ localPasses++ },{ localPasses++ },{ localPasses++ })
        assertEquals(3,localPasses);assertEquals(1,polling.waitMillis())
        assertEquals(deadline,UpdateInbox(Database(path)).pollRetryAt())
        now=deadline
        assertEquals(emptyList(),polling.receive { emptyList() })
        assertEquals(0,UpdateInbox(Database(path)).pollRetryAt())
        polling=schedule()
        assertEquals(listOf(TgUpdate(1)),polling.receive { listOf(TgUpdate(1)) })
    }

    @Test fun `failure to persist a polling delay stops the cycle before local work is acknowledged`() {
        val polling=UpdatePollingSchedule(saveDeadline={ error("Storage unavailable") },nowMillis={ 1000L })
        assertFailsWith<IllegalStateException> {
            runQueuedBotCycle({ polling.receive { throw TelegramFailure(FailureKind.RETRY_LATER,429,3600) } },
                { fail("No batch may be accepted") },{ fail("The durable failure must propagate") },{}, {})
        }
    }

    @Test fun `hour long polling limit keeps local work running without a synthetic empty response`() {
        var now=0L
        val polling=UpdatePollingSchedule { now }
        var requests=0
        var accepted=0
        var processed=0
        var maintained=0
        var finished=0
        var queueDrained=false
        val order=mutableListOf<String>()
        fun cycle()=runQueuedBotCycle(
            { polling.receive {
                requests++;order+="receive"
                if(requests==1) throw TelegramFailure(FailureKind.RETRY_LATER,429,3600)
                emptyList()
            } },
            { assertTrue(it.isEmpty());accepted++;queueDrained=true;order+="accept" },
            { processed++;order+="process" },
            { finished++;order+="finish" },
            { maintained++;order+="maintain" },
        )
        repeat(3600) {
            cycle()
            assertEquals(1,requests);assertEquals(0,accepted);assertFalse(queueDrained)
            assertEquals(1000,polling.waitMillis())
            now+=polling.waitMillis()
        }
        assertEquals(3_600_000,now)
        assertEquals(3600,processed);assertEquals(3600,finished);assertEquals(3600,maintained)
        assertEquals(listOf("receive","process","finish","maintain"),order.take(4))
        assertEquals(0,polling.waitMillis())
        cycle()
        assertEquals(2,requests);assertEquals(1,accepted);assertTrue(queueDrained)
        assertEquals(listOf("receive","accept","process","finish","maintain"),order.takeLast(5))
    }

    @Test fun `saved events and previously drained polls finish while other polls still await Telegram drain`() {
        var now=100L
        val polling=UpdatePollingSchedule { now }
        val pending=mutableListOf("already-saved")
        val handled=mutableListOf<String>()
        var pollRequests=0
        var receivedBatches=0
        var previouslyDrained=1
        var waitingForDrain=1
        var completedPolls=0
        var deliveries=0
        fun cycle()=runQueuedBotCycle(
            { polling.receive { pollRequests++;throw TelegramFailure(FailureKind.RETRY_LATER,429,3600) } },
            { receivedBatches++;if(it.isEmpty()) { previouslyDrained+=waitingForDrain;waitingForDrain=0 } },
            { handled+=pending;pending.clear() },
            { completedPolls+=previouslyDrained;previouslyDrained=0 },
            { deliveries++ },
        )
        cycle()
        pending+="ready-local-retry";now+=1000;cycle()
        assertEquals(listOf("already-saved","ready-local-retry"),handled)
        assertEquals(1,pollRequests);assertEquals(0,receivedBatches)
        assertEquals(1,completedPolls);assertEquals(1,waitingForDrain);assertEquals(2,deliveries)
        assertEquals(1000,polling.waitMillis())
    }

    @Test fun `retry delay begins after failure and waits only the remainder near its deadline`() {
        var now=0L
        val polling=UpdatePollingSchedule { now }
        assertNull(polling.receive { now=2000;throw TelegramFailure(FailureKind.RETRY_LATER,429,3600) })
        now=3_600_000
        assertNull(polling.receive { fail("Polling resumed before the full retry_after elapsed") })
        assertEquals(1000,polling.waitMillis())
        now=3_601_750
        assertNull(polling.receive { fail("Polling resumed 250 milliseconds early") })
        assertEquals(250,polling.waitMillis())
        now=3_602_000
        assertEquals(listOf(TgUpdate(1)),polling.receive { listOf(TgUpdate(1)) })
        assertEquals(0,polling.waitMillis())
    }

    @Test fun `connection failures use a finite polling deadline and do not spin`() {
        var now=0L
        val polling=UpdatePollingSchedule { now }
        assertNull(polling.receive { throw TelegramFailure(FailureKind.UNCERTAIN) })
        assertEquals(1000,polling.waitMillis())
        now=2999
        assertNull(polling.receive { fail("Expected the default three second delay") })
        assertEquals(1,polling.waitMillis())
        now=3000
        assertEquals(emptyList(),polling.receive { emptyList() })
    }

    @Test fun `fatal polling errors and background budget deferral keep their existing control flow`() {
        val failures=listOf(TelegramFailure(FailureKind.REJECTED,401),TelegramFailure(FailureKind.REJECTED,409),
            TelegramFailure(FailureKind.RETRY_LATER,retryAfter=1,backgroundDeferred=true))
        failures.forEach { failure ->
            val polling=UpdatePollingSchedule { 0L }
            assertSame(failure,assertFailsWith<TelegramFailure> { polling.receive { throw failure } })
            assertEquals(0,polling.waitMillis())
        }
    }
}
