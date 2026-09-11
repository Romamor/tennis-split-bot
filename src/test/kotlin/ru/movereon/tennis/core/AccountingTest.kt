package ru.movereon.tennis.core

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountingTest {
    private val a = ParticipantId("andrey")
    private val b = ParticipantId("boris")
    private val v = ParticipantId("vera")

    @Test fun `round whole rubles per player before combining the plus-one`() {
        val allocation = calculateTraining(Training(
            listOf(PlayerSlot(a, 120), PlayerSlot(b, 120), PlayerSlot(v, 120), PlayerSlot(v, 120, true)),
            listOf(ExpensePayment(a, 350), ExpensePayment(b, 400)),
        ))
        assertEquals(listOf(188L, 188L, 187L, 187L), allocation.slotShares)
        assertEquals(mapOf(a to 188L, b to 188L, v to 374L), allocation.participantShares)
        assertEquals(mapOf(a to 162L, b to 212L, v to -374L), applyEntries(emptyMap(), allocation.entries))
    }

    @Test fun `plus-one can play a different amount of time`() {
        val allocation = calculateTraining(Training(
            listOf(PlayerSlot(v, 120), PlayerSlot(v, 60, true), PlayerSlot(a, 60)),
            listOf(ExpensePayment(a, 600)),
        ))
        assertEquals(listOf(300L, 150L, 150L), allocation.slotShares)
        assertEquals(mapOf(v to 450L, a to 150L), allocation.participantShares)
    }

    @Test fun `several guests have separate rounded shares charged to the inviter`() {
        val result=calculateTraining(Training(listOf(PlayerSlot(a,60),PlayerSlot(a,60,true),PlayerSlot(a,60,true),PlayerSlot(b,60)),listOf(ExpensePayment(b,751))))
        assertEquals(listOf(188L,188L,188L,187L),result.slotShares)
        assertEquals(mapOf(a to 564L,b to 187L),result.participantShares)
        assertEquals(mapOf(a to -564L,b to 564L),applyEntries(emptyMap(),result.entries))
    }

    @Test fun `a non-playing participant may pay for the table`() {
        val allocation = calculateTraining(Training(listOf(PlayerSlot(a, 60), PlayerSlot(b, 60)), listOf(ExpensePayment(v, 100))))
        assertEquals(mapOf(a to -50L, b to -50L, v to 100L), applyEntries(emptyMap(), allocation.entries))
    }

    @Test fun `one ruble is assigned deterministically`() {
        val training = Training(listOf(PlayerSlot(a, 60), PlayerSlot(b, 60), PlayerSlot(v, 60)), listOf(ExpensePayment(v, 1)))
        assertEquals(listOf(1L, 0L, 0L), calculateTraining(training).slotShares)
        assertEquals(calculateTraining(training), calculateTraining(training))
    }

    @Test fun `large products do not overflow during proportional allocation`() {
        val allocation = calculateTraining(Training(
            listOf(PlayerSlot(a, 2), PlayerSlot(b, 1)), listOf(ExpensePayment(a, Long.MAX_VALUE)),
        ))
        assertEquals(Long.MAX_VALUE, allocation.slotShares.sum())
        assertEquals(listOf(6148914691236517205L, 3074457345618258602L), allocation.slotShares)
    }

    @Test fun `invalid training shapes and overflowing totals are rejected`() {
        val normal = Training(listOf(PlayerSlot(a, 60)), listOf(ExpensePayment(a, 100)))
        val invalid = listOf(
            normal.copy(players = emptyList()), normal.copy(payments = emptyList()),
            normal.copy(players = listOf(PlayerSlot(a, 0))),
            normal.copy(players = listOf(PlayerSlot(a, -1))),
            normal.copy(players = listOf(PlayerSlot(a, 60, true))),
            normal.copy(players = listOf(PlayerSlot(a, 60), PlayerSlot(a, 30))),
            normal.copy(payments = listOf(ExpensePayment(a, 0))),
            normal.copy(payments = listOf(ExpensePayment(a, -1))),
            normal.copy(payments = listOf(ExpensePayment(a, 100), ExpensePayment(a, 100))),
        )
        invalid.forEach { assertEquals(ErrorCode.INVALID_INPUT, assertFailsWith<AccountingException> { calculateTraining(it) }.code) }
        val overflowing = listOf(
            normal.copy(players = listOf(PlayerSlot(a, Long.MAX_VALUE), PlayerSlot(b, 1))),
            normal.copy(payments = listOf(ExpensePayment(a, Long.MAX_VALUE), ExpensePayment(b, 1))),
        )
        overflowing.forEach { assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> { calculateTraining(it) }.code) }
    }

    @Test fun `amount parsing rejects fractional and ambiguous input`() {
        assertEquals(500L, parseAmount(" 500 "))
        assertEquals(Long.MAX_VALUE, parseAmount(Long.MAX_VALUE.toString()))
        listOf("", "0", "-10", "+10", "1.50", "1,50", "1e3", "1 000", "500 ₽").forEach {
            assertEquals(ErrorCode.INVALID_INPUT, assertFailsWith<AccountingException> { parseAmount(it) }.code)
        }
        assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> { parseAmount("9223372036854775808") }.code)
    }

    @Test fun `balance validation handles large cancelling totals`() {
        val balances = mapOf(a to Long.MAX_VALUE, b to Long.MAX_VALUE, v to -Long.MAX_VALUE, ParticipantId("sasha") to -Long.MAX_VALUE)
        validateBalances(balances)
        val plan = suggestTransfers(balances)
        assertEquals(2, plan.size)
        val settled = applyEntries(balances, plan.flatMap { listOf(BalanceEntry(it.from, it.amount), BalanceEntry(it.to, -it.amount)) })
        assertTrue(settled.values.all { it == 0L })
    }

    @Test fun `invalid or overflowing operations leave input unchanged`() {
        val balances = mapOf(a to Long.MAX_VALUE, b to -Long.MAX_VALUE)
        assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> {
            applyEntries(balances, listOf(BalanceEntry(a, 1), BalanceEntry(b, -1)))
        }.code)
        assertEquals(ErrorCode.UNBALANCED, assertFailsWith<AccountingException> {
            applyEntries(balances, listOf(BalanceEntry(a, 1)))
        }.code)
        assertEquals(mapOf(a to Long.MAX_VALUE, b to -Long.MAX_VALUE), balances)
        assertEquals(ErrorCode.UNBALANCED, assertFailsWith<AccountingException> { suggestTransfers(mapOf(a to 1)) }.code)
        assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> { validateBalances(mapOf(a to Long.MIN_VALUE)) }.code)
    }

    @Test fun `a replacement batch checks the final balance rather than intermediate sums`() {
        val entries = listOf(BalanceEntry(a, Long.MAX_VALUE), BalanceEntry(a, Long.MAX_VALUE), BalanceEntry(a, -Long.MAX_VALUE), BalanceEntry(b, -Long.MAX_VALUE))
        assertEquals(mapOf(a to Long.MAX_VALUE, b to -Long.MAX_VALUE), applyEntries(emptyMap(), entries))
    }

    @Test fun `generated allocations preserve totals and settlement closes every balance`() {
        val random = Random(90208)
        repeat(1000) {
            val count = random.nextInt(1, 15)
            val players = (0 until count).map { PlayerSlot(ParticipantId("p$it"), random.nextLong(1, 361)) }
            val total = random.nextLong(1, 1_000_000)
            val allocation = calculateTraining(Training(players, listOf(ExpensePayment(players.last().participant, total))))
            assertEquals(total, allocation.slotShares.sum())
            assertTrue(allocation.slotShares.all { it >= 0 })
            val time = players.sumOf { it.minutes }.toBigInteger()
            players.forEachIndexed { index, slot ->
                // Each rounded share differs from its rational entitlement by less than one ruble.
                val difference = (allocation.slotShares[index].toBigInteger() * time - total.toBigInteger() * slot.minutes.toBigInteger()).abs()
                assertTrue(difference < time)
            }
            val balances = applyEntries(emptyMap(), allocation.entries)
            val original = balances.toMap()
            val plan = suggestTransfers(balances)
            assertEquals(plan, suggestTransfers(balances.entries.reversed().associate { it.toPair() }))
            assertTrue(plan.all { it.amount > 0 && it.from != it.to })
            assertTrue(plan.size <= maxOf(0, balances.values.count { it != 0L } - 1))
            val settled = applyEntries(balances, plan.flatMap { listOf(BalanceEntry(it.from, it.amount), BalanceEntry(it.to, -it.amount)) })
            assertTrue(settled.values.all { it == 0L })
            assertEquals(original, balances)
            assertEquals(BigInteger.ZERO, allocation.entries.fold(BigInteger.ZERO) { sum, entry -> sum + entry.amount.toBigInteger() })
        }
    }
}
