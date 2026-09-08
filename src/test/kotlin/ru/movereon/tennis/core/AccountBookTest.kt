package ru.movereon.tennis.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountBookTest {
    private val a = ParticipantId("andrey")
    private val b = ParticipantId("boris")
    private val v = ParticipantId("vera")
    private val s = ParticipantId("sasha")
    private val andrey = Actor("telegram:1", a)
    private val boris = Actor("telegram:2", b)
    private val vera = Actor("telegram:3", v)
    private fun book() = AccountBook("tennis-group")
    private fun training(cost: Long) = Training(listOf(PlayerSlot(a, 60), PlayerSlot(b, 60)), listOf(ExpensePayment(a, cost)))
    private fun record(id: String = "transfer", amount: Long = 100) = Command.RecordTransfer(id, a, b, amount)

    private fun assertBalances(book: AccountBook, aa: Long, bb: Long, vv: Long, ss: Long = 0) {
        val balances = book.balances()
        assertEquals(listOf(aa, bb, vv, ss), listOf(a, b, v, s).map { balances.getOrDefault(it, 0) })
        validateBalances(balances)
        val replayed = book.journal().fold(emptyMap<ParticipantId, Long>()) { current, event -> applyEntries(current, event.entries) }
        assertEquals(balances, replayed)
    }

    @Test fun `three trainings advance partial payment and clarification match the specification`() {
        val book = book()
        book.execute("t1", andrey, Command.PostTraining("training-1", Training(
            listOf(PlayerSlot(a, 120), PlayerSlot(b, 120), PlayerSlot(v, 120), PlayerSlot(v, 120, true)),
            listOf(ExpensePayment(a, 350), ExpensePayment(b, 400)),
        )))
        assertBalances(book, 162, 212, -374)
        book.execute("advance", vera, Command.RecordTransfer("advance", v, b, 500))
        assertBalances(book, 162, -288, 126)
        book.execute("t2", andrey, Command.PostTraining("training-2", Training(
            listOf(PlayerSlot(a, 60), PlayerSlot(b, 120), PlayerSlot(v, 120), PlayerSlot(s, 60)),
            listOf(ExpensePayment(b, 900)),
        )))
        assertBalances(book, 12, 312, -174, -150)
        // Linking Telegram changes identity metadata in the application, not financial entries.
        book.execute("t3", andrey, Command.PostTraining("training-3", Training(
            listOf(a, b, v, s).map { PlayerSlot(it, 60) }, listOf(ExpensePayment(a, 300), ExpensePayment(s, 300)),
        )))
        assertBalances(book, 162, 162, -324)
        assertEquals(listOf(SuggestedTransfer(v, a, 162), SuggestedTransfer(v, b, 162)), suggestTransfers(book.balances()))
        book.execute("partial", vera, Command.RecordTransfer("partial", v, a, 100))
        assertBalances(book, 62, 162, -224)
        book.execute("review", andrey, Command.ChangeTransfer("partial", 1, TransferAction.REVIEW))
        assertBalances(book, 162, 162, -324)
        book.execute("confirm", andrey, Command.ChangeTransfer("partial", 2, TransferAction.CONFIRM))
        assertBalances(book, 62, 162, -224)
        assertEquals(setOf(SuggestedTransfer(v, a, 62), SuggestedTransfer(v, b, 162)), suggestTransfers(book.balances()).toSet())
    }

    @Test fun `advance requires no debt and can be recorded by recipient`() {
        val book = book()
        book.execute("advance", boris, record(amount = 500))
        assertBalances(book, 500, -500, 0)
    }

    @Test fun `same command is idempotent even after subsequent events`() {
        val book = book()
        val command = record()
        val first = book.execute("same", andrey, command)
        book.execute("review", boris, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        assertEquals(first, book.execute("same", andrey, command))
        assertEquals(2, book.journal().size)
        assertBalances(book, 0, 0, 0)
        assertEquals(ErrorCode.COMMAND_CONFLICT, assertFailsWith<AccountingException> { book.execute("same", andrey, record(amount = 200)) }.code)
        assertEquals(ErrorCode.COMMAND_CONFLICT, assertFailsWith<AccountingException> { book.execute("same", boris, command) }.code)
    }

    @Test fun `concurrent retries produce one operation`() {
        val book = book()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..40).map { Callable { book.execute("same", andrey, record()) } }).map { it.get() }
            assertEquals(1, results.distinct().size)
            assertEquals(1, book.journal().size)
            assertBalances(book, 100, -100, 0)
        } finally { pool.shutdownNow() }
    }

    @Test fun `different command IDs cannot repeat an old transition`() {
        val book = book()
        book.execute("pay", andrey, record())
        book.execute("review", boris, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        assertEquals(ErrorCode.STALE_VERSION, assertFailsWith<AccountingException> {
            book.execute("review-again", boris, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        }.code)
        assertEquals(ErrorCode.INVALID_STATE, assertFailsWith<AccountingException> {
            book.execute("review-again", boris, Command.ChangeTransfer("transfer", 2, TransferAction.REVIEW))
        }.code)
        assertEquals(2, book.journal().size)
        assertBalances(book, 0, 0, 0)
    }

    @Test fun `only a party can record and only the initiator can resolve a review`() {
        val book = book()
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<AccountingException> { book.execute("wrong", vera, record()) }.code)
        book.execute("pay", andrey, record())
        book.execute("review", boris, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<AccountingException> {
            book.execute("wrong-confirm", andrey, Command.ChangeTransfer("transfer", 2, TransferAction.CONFIRM))
        }.code)
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<AccountingException> {
            book.execute("wrong-cancel", andrey, Command.ChangeTransfer("transfer", 2, TransferAction.CANCEL))
        }.code)
        assertBalances(book, 0, 0, 0)
        book.execute("confirm", boris, Command.ChangeTransfer("transfer", 2, TransferAction.CONFIRM))
        assertBalances(book, 100, -100, 0)
    }

    @Test fun `a representative is recorded and the same actor resolves the review`() {
        val book = book()
        val representative = Actor("organizer:9", a)
        book.execute("pay", representative, record())
        book.execute("review", representative, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<AccountingException> {
            book.execute("confirm", andrey, Command.ChangeTransfer("transfer", 2, TransferAction.CONFIRM))
        }.code)
        book.execute("confirm", representative, Command.ChangeTransfer("transfer", 2, TransferAction.CONFIRM))
        assertTrue(book.journal().all { it.actorId == representative.id && it.representedParty == a })
    }

    @Test fun `cancelled erroneous transfer stays in history without financial effect`() {
        val book = book()
        book.execute("pay", andrey, record())
        book.execute("review", andrey, Command.ChangeTransfer("transfer", 1, TransferAction.REVIEW))
        book.execute("cancel", andrey, Command.ChangeTransfer("transfer", 2, TransferAction.CANCEL))
        assertBalances(book, 0, 0, 0)
        assertEquals(TransferStatus.CANCELLED, book.transfer("transfer")?.status)
        assertEquals(3, book.journal().size)
        assertEquals(ErrorCode.INVALID_STATE, assertFailsWith<AccountingException> {
            book.execute("restore", andrey, Command.ChangeTransfer("transfer", 3, TransferAction.CONFIRM))
        }.code)
    }

    @Test fun `revising and cancelling a training preserves actual transfers`() {
        val book = book()
        book.execute("t", andrey, Command.PostTraining("training", training(100)))
        book.execute("pay", boris, Command.RecordTransfer("return", b, a, 10))
        assertBalances(book, 40, -40, 0)
        book.execute("edit", andrey, Command.PostTraining("training", training(200), 1))
        assertBalances(book, 90, -90, 0)
        assertEquals(ErrorCode.STALE_VERSION, assertFailsWith<AccountingException> {
            book.execute("stale", andrey, Command.PostTraining("training", training(300), 1))
        }.code)
        book.execute("cancel", andrey, Command.CancelTraining("training", 2))
        assertBalances(book, -10, 10, 0)
        assertEquals(TransferStatus.ACTIVE, book.transfer("return")?.status)
        assertEquals(ErrorCode.INVALID_STATE, assertFailsWith<AccountingException> {
            book.execute("cancel-twice", andrey, Command.CancelTraining("training", 3))
        }.code)
        assertEquals(ErrorCode.INVALID_STATE, assertFailsWith<AccountingException> {
            book.execute("restore", andrey, Command.PostTraining("training", training(100), 3))
        }.code)
    }

    @Test fun `overflow is atomic and does not reserve a failed command ID`() {
        val book = book()
        book.execute("max", andrey, record("max", Long.MAX_VALUE))
        val journal = book.journal()
        assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> { book.execute("next", andrey, record("next", 1)) }.code)
        assertEquals(journal, book.journal())
        assertEquals(null, book.transfer("next"))
        book.execute("next", boris, Command.RecordTransfer("next", b, a, 1))
        assertBalances(book, Long.MAX_VALUE - 1, -Long.MAX_VALUE + 1, 0)
    }

    @Test fun `mutable caller collections do not corrupt balances or deduplication`() {
        val book = book()
        val players = mutableListOf(PlayerSlot(a, 60), PlayerSlot(b, 60))
        val command = Command.PostTraining("training", Training(players, listOf(ExpensePayment(a, 100))))
        val receipt = book.execute("t", andrey, command)
        players.clear()
        assertEquals(receipt, book.execute("t", andrey, Command.PostTraining("training", training(100))))
        val exported = book.balances().toMutableMap()
        exported[a] = 999
        val exportedJournal = book.journal()
        @Suppress("UNCHECKED_CAST")
        (exportedJournal[0].entries as? MutableList<BalanceEntry>)?.clear()
        assertBalances(book, 50, -50, 0)
    }

    @Test fun `separate groups may use the same source and command IDs`() {
        val first = AccountBook("first")
        val second = AccountBook("second")
        first.execute("pay", andrey, record())
        second.execute("pay", andrey, record(amount = 200))
        assertBalances(first, 100, -100, 0)
        assertBalances(second, 200, -200, 0)
    }
}
