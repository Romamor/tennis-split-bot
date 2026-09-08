package ru.movereon.tennis.storage

import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class SqliteAccountingStoreTest {
    @TempDir lateinit var directory: Path
    private val a = ParticipantId("andrey")
    private val b = ParticipantId("boris")
    private val v = ParticipantId("vera")
    private val s = ParticipantId("sasha")
    private val andrey = Actor("telegram:1", a)
    private val boris = Actor("telegram:2", b)
    private val vera = Actor("telegram:3", v)
    private val clock = Clock.fixed(Instant.parse("2026-09-08T10:15:30Z"), ZoneOffset.UTC)
    private val group = "tennis"
    private fun path() = directory.resolve("accounting.sqlite")
    private fun store() = SqliteAccountingStore(path(), clock)
    private fun transfer(id: String = "transfer", amount: Long = 100) = Command.RecordTransfer(id, a, b, amount)
    private fun training(amount: Long = 100) = Training(listOf(PlayerSlot(a, 60), PlayerSlot(b, 60)), listOf(ExpensePayment(a, amount)))
    private fun sql(text: String) = DriverManager.getConnection("jdbc:sqlite:${path()}").use { connection ->
        connection.createStatement().use { it.execute(text) }
    }

    @Test fun `empty real database is initialized and verified`() {
        val store = store()
        assertTrue(Files.size(path()) > 0)
        assertEquals(emptyMap(), store.balances(group))
        assertEquals(emptyList(), store.history(group))
        assertEquals(emptyList(), store.pendingEvents())
        assertEquals(IntegrityReport(0, 0), store.verifyIntegrity())
    }

    @Test fun `source details receipts and balances survive reopening`() {
        val command = Command.PostTraining("training", Training(
            listOf(PlayerSlot(a, 120), PlayerSlot(b, 120), PlayerSlot(v, 120), PlayerSlot(v, 120, true)),
            listOf(ExpensePayment(a, 350), ExpensePayment(b, 400))))
        val details = OperationDetails(LocalDate.parse("2026-09-07"), "Первый стол; второй стол — оплата Бориса")
        val receipt = store().execute(group, "post", andrey, command, details)
        val reopened = store()
        assertEquals(mapOf(a to 162L, b to 212L, v to -374L), reopened.balances(group))
        val operation = reopened.history(group).single()
        assertEquals(command, operation.command)
        assertEquals(details, operation.details)
        assertEquals(andrey, operation.actor)
        assertEquals(clock.instant(), operation.recordedAt)
        assertEquals(receipt, operation.receipt)
        assertEquals(receipt, reopened.execute(group, "post", andrey, command, details))
        assertEquals(IntegrityReport(1, 1), reopened.verifyIntegrity())
        assertEquals(1, reopened.pendingEvents().size)
    }

    @Test fun `full scenario stays correct when reopening after every operation`() {
        val commands = listOf(
            Triple(andrey, Command.PostTraining("t1", Training(
                listOf(PlayerSlot(a, 120), PlayerSlot(b, 120), PlayerSlot(v, 120), PlayerSlot(v, 120, true)),
                listOf(ExpensePayment(a, 350), ExpensePayment(b, 400)))), listOf(162L, 212L, -374L, 0L)),
            Triple(vera, Command.RecordTransfer("advance", v, b, 500), listOf(162L, -288L, 126L, 0L)),
            Triple(andrey, Command.PostTraining("t2", Training(
                listOf(PlayerSlot(a, 60), PlayerSlot(b, 120), PlayerSlot(v, 120), PlayerSlot(s, 60)),
                listOf(ExpensePayment(b, 900)))), listOf(12L, 312L, -174L, -150L)),
            Triple(andrey, Command.PostTraining("t3", Training(listOf(a, b, v, s).map { PlayerSlot(it, 60) },
                listOf(ExpensePayment(a, 300), ExpensePayment(s, 300)))), listOf(162L, 162L, -324L, 0L)),
            Triple(vera, Command.RecordTransfer("partial", v, a, 100), listOf(62L, 162L, -224L, 0L)),
            Triple(andrey, Command.ChangeTransfer("partial", 1, TransferAction.REVIEW), listOf(162L, 162L, -324L, 0L)),
            Triple(andrey, Command.ChangeTransfer("partial", 2, TransferAction.CONFIRM), listOf(62L, 162L, -224L, 0L)),
        )
        commands.forEachIndexed { index, (actor, command, expected) ->
            store().execute(group, "step-$index", actor, command)
            val reopened = store()
            assertEquals(expected, listOf(a, b, v, s).map { reopened.balances(group).getOrDefault(it, 0) })
            assertEquals(IntegrityReport(1, index + 1), reopened.verifyIntegrity())
        }
        assertEquals(setOf(SuggestedTransfer(v, a, 62), SuggestedTransfer(v, b, 162)), suggestTransfers(store().balances(group)).toSet())
    }

    @Test fun `review initiator and cancellation persist across restart`() {
        store().execute(group, "pay", andrey, transfer())
        store().execute(group, "review", boris, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        assertEquals(boris.id, store().transfer(group, "transfer")?.reviewInitiator)
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<AccountingException> {
            store().execute(group, "wrong", andrey, Command.ChangeTransfer("transfer", 2, TransferAction.CONFIRM))
        }.code)
        store().execute(group, "cancel", boris, Command.ChangeTransfer("transfer", 2, TransferAction.CANCEL))
        assertEquals(TransferStatus.CANCELLED, store().transfer(group, "transfer")?.status)
        assertTrue(store().balances(group).values.all { it == 0L })
        assertEquals(IntegrityReport(1, 3), store().verifyIntegrity())
    }

    @Test fun `revision and cancellation affect only the training`() {
        store().execute(group, "post", andrey, Command.PostTraining("training", training()))
        store().execute(group, "return", boris, Command.RecordTransfer("return", b, a, 10))
        store().execute(group, "edit", andrey, Command.PostTraining("training", training(200), 1))
        assertEquals(mapOf(a to 90L, b to -90L), store().balances(group))
        assertEquals(ErrorCode.STALE_VERSION, assertFailsWith<AccountingException> {
            store().execute(group, "stale", andrey, Command.CancelTraining("training", 1))
        }.code)
        store().execute(group, "cancel", andrey, Command.CancelTraining("training", 2))
        assertEquals(mapOf(a to -10L, b to 10L), store().balances(group))
        assertEquals(IntegrityReport(1, 4), store().verifyIntegrity())
    }

    @Test fun `duplicate command cannot change its author data or source details`() {
        val command = transfer()
        val receipt = store().execute(group, "same", andrey, command, OperationDetails(note = "Аванс"))
        val reopened = store()
        assertEquals(receipt, reopened.execute(group, "same", andrey, command, OperationDetails(note = "Аванс")))
        val different = listOf(
            Triple(boris, command, OperationDetails(note = "Аванс")),
            Triple(andrey, transfer(amount = 200), OperationDetails(note = "Аванс")),
            Triple(andrey, command, OperationDetails(note = "Другое пояснение")),
        )
        different.forEach { (actor, input, details) ->
            assertEquals(ErrorCode.COMMAND_CONFLICT, assertFailsWith<AccountingException> {
                reopened.execute(group, "same", actor, input, details)
            }.code)
        }
        assertEquals(IntegrityReport(1, 1), reopened.verifyIntegrity())
    }

    @Test fun `concurrent retries through independent connections commit once`() {
        val stores = List(4) { store() }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val receipts = pool.invokeAll((0 until 24).map { index -> Callable {
                stores[index % stores.size].execute(group, "same", andrey, transfer())
            } }).map { it.get() }
            assertEquals(1, receipts.distinct().size)
            assertEquals(IntegrityReport(1, 1), store().verifyIntegrity())
            assertEquals(1, store().pendingEvents().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `concurrent edits cannot overwrite a newer revision`() {
        store().execute(group, "post", andrey, Command.PostTraining("training", training()))
        val stores = List(2) { store() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll((0..1).map { i -> Callable {
                try {
                    stores[i].execute(group, "edit-$i", andrey, Command.PostTraining("training", training(200L + i * 100), 1))
                    null
                } catch (failure: AccountingException) { failure.code }
            } }).map { it.get() }
            assertEquals(1, outcomes.count { it == null })
            assertEquals(1, outcomes.count { it == ErrorCode.STALE_VERSION })
            assertEquals(IntegrityReport(1, 2), store().verifyIntegrity())
        } finally { pool.shutdownNow() }
    }

    @Test fun `SQL failure after journal insertion rolls back every table and allows retry`() {
        val store = store()
        sql("CREATE TRIGGER reject_notification BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertFailsWith<SQLException> { store.execute(group, "pay", andrey, transfer()) }
        assertEquals(emptyMap(), store.balances(group))
        assertEquals(emptyList(), store.history(group))
        assertEquals(emptyList(), store.pendingEvents())
        assertEquals(IntegrityReport(0, 0), store.verifyIntegrity())
        sql("DROP TRIGGER reject_notification")
        assertEquals(Receipt(1, 1), store.execute(group, "pay", andrey, transfer()))
        assertEquals(IntegrityReport(1, 1), store().verifyIntegrity())
    }

    @Test fun `overflow does not persist a partial operation or consume the command ID`() {
        store().execute(group, "max", andrey, transfer("max", Long.MAX_VALUE))
        assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> {
            store().execute(group, "next", andrey, transfer("next", 1))
        }.code)
        assertEquals(null, store().transfer(group, "next"))
        assertEquals(IntegrityReport(1, 1), store().verifyIntegrity())
        store().execute(group, "next", boris, Command.RecordTransfer("next", b, a, 1))
        assertEquals(mapOf(a to Long.MAX_VALUE - 1, b to -Long.MAX_VALUE + 1), store().balances(group))
    }

    @Test fun `notification events persist and acknowledgement is idempotent`() {
        store().execute(group, "one", andrey, transfer("one"))
        store().execute(group, "two", andrey, transfer("two"))
        assertEquals(listOf(1L, 2L), store().pendingEvents().map { it.sequence })
        assertTrue(store().acknowledge(group, 1))
        assertFalse(store().acknowledge(group, 1))
        assertFalse(store().acknowledge("another", 2))
        assertEquals(listOf(2L), store().pendingEvents().map { it.sequence })
        store().execute(group, "one", andrey, transfer("one"))
        assertEquals(listOf(2L), store().pendingEvents().map { it.sequence })
        assertEquals(IntegrityReport(1, 2), store().verifyIntegrity())
    }

    @Test fun `backup includes committed WAL data and restores independently`() {
        val source = store()
        source.execute(group, "one", andrey, transfer("one"))
        DriverManager.getConnection("jdbc:sqlite:${path()}").use { reader ->
            reader.autoCommit = false
            reader.createStatement().use { statement -> statement.executeQuery("SELECT * FROM operations").use { assertTrue(it.next()) } }
            source.execute(group, "two", andrey, transfer("two", 250))
            assertTrue(Files.exists(Path.of("${path()}-wal")))
            val backup = source.backup(directory.resolve("backup.sqlite"))
            val restored = SqliteAccountingStore(backup, clock)
            assertEquals(source.balances(group), restored.balances(group))
            assertEquals(source.history(group), restored.history(group))
            assertEquals(source.pendingEvents(), restored.pendingEvents())
            assertEquals(IntegrityReport(1, 2), restored.verifyIntegrity())
            assertFailsWith<IllegalArgumentException> { source.backup(backup) }
            source.execute(group, "three", andrey, transfer("three", 50))
            assertEquals(IntegrityReport(1, 2), restored.verifyIntegrity())
            assertEquals(IntegrityReport(1, 3), source.verifyIntegrity())
            reader.rollback()
        }
    }

    @Test fun `groups and command IDs are isolated and text is parameterized`() {
        val strangeGroup = "tennis'); DROP TABLE operations; --"
        val note = "'+1'; Привет\nВторая строка"
        val store = store()
        store.execute(group, "pay", andrey, transfer())
        store.execute(strangeGroup, "pay", andrey, transfer(amount = 200), OperationDetails(note = note))
        assertEquals(mapOf(a to 100L, b to -100L), store.balances(group))
        assertEquals(mapOf(a to 200L, b to -200L), store.balances(strangeGroup))
        assertEquals(note, store.history(strangeGroup).single().details.note)
        assertEquals(IntegrityReport(2, 2), store.verifyIntegrity())
    }

    @Test fun `a database with an unsupported schema is left intact`() {
        sql("PRAGMA user_version=99")
        assertFailsWith<IllegalStateException> { store() }
        DriverManager.getConnection("jdbc:sqlite:${path()}").use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("PRAGMA user_version").use { rows -> rows.next(); assertEquals(99, rows.getInt(1)) } }
        }
    }

    @Test fun `an unrelated database is not initialized as accounting storage`() {
        sql("CREATE TABLE unrelated(value TEXT)")
        assertFailsWith<IllegalArgumentException> { store() }
        DriverManager.getConnection("jdbc:sqlite:${path()}").use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='group_state'").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) } }
        }
    }

    @Test fun `integrity verification detects balanced journal corruption`() {
        val store = store()
        store.execute(group, "pay", andrey, transfer())
        sql("UPDATE balance_entries SET amount=amount*2")
        assertFailsWith<StorageCorruption> { store.verifyIntegrity() }
    }

    @Test fun `malformed checkpoint fails without adding an operation`() {
        val store = store()
        store.execute(group, "pay", andrey, transfer())
        sql("UPDATE group_state SET checkpoint_json='not-json'")
        assertFailsWith<StorageCorruption> { store.execute(group, "next", andrey, transfer("next")) }
        assertEquals(1, store.history(group).size)
    }
}
