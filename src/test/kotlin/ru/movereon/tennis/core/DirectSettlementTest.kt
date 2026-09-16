package ru.movereon.tennis.core

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

class DirectSettlementTest {
    private fun balances(vararg amounts: Long) = amounts.mapIndexed { i, amount -> ParticipantId("p%02d".format(i)) to amount }.toMap()
    private fun maximum(plan: List<SuggestedTransfer>) = plan.groupingBy { it.from }.eachCount().values.maxOrNull() ?: 0
    private fun verify(b: Map<ParticipantId, Long>, p: List<SuggestedTransfer>) {
        assertTrue(p.all { it.amount > 0 && b.getValue(it.from) < 0 && b.getValue(it.to) > 0 })
        assertEquals(p.size, p.map { it.from to it.to }.toSet().size)
        assertTrue(applyEntries(b, p.flatMap { listOf(BalanceEntry(it.from, it.amount), BalanceEntry(it.to, -it.amount)) }).values.all { it == 0L })
        assertEquals(p, suggestTransfers(b.entries.reversed().associate { it.toPair() }))
    }
    @Test fun `matching complete amounts removes an unnecessary fourth transfer`() {
        val b = balances(800, 700, -700, -500, -300)
        val p = suggestTransfers(b)
        assertEquals(3, p.size); assertEquals(1, maximum(p)); verify(b, p)
    }
    @Test fun `approved twenty person case uses one extra payment to reduce outgoing degree`() {
        val b = balances(-6,-89,-118,-53,-80,-31,-12,-24,-113,-42,91,78,80,47,83,32,60,6,58,33)
        val p = suggestTransfers(b)
        // The exact experiment proves 15; the accepted heuristic returns 16 / 2.
        assertEquals(16, p.size); assertEquals(2, maximum(p)); verify(b, p)
    }
    @Test fun `heuristic is not presented as globally optimal`() {
        val b = balances(-321,-530,-183,22,85,73,38,85,33,98,30,95,38,51,99,85,95,18,48,41)
        val p = suggestTransfers(b)
        // Explicitly preserve the accepted limitation: exact minimum 17, this portfolio 19.
        assertEquals(19, p.size); assertEquals(7, maximum(p)); verify(b, p)
    }
    @Test fun `one sender pays every receiver directly and larger groups have no prototype limit`() {
        val b = balances(-39, *LongArray(39) { 1 })
        val p = suggestTransfers(b)
        assertEquals(39, p.size); assertEquals(39, maximum(p)); verify(b, p)
        assertEquals(emptyList(), suggestTransfers(balances(0, 0)))
    }
    @Test fun `large amounts and cancelling totals do not overflow`() {
        val b = balances(Long.MAX_VALUE, Long.MAX_VALUE-2, -Long.MAX_VALUE+1, -Long.MAX_VALUE+1, 0)
        verify(b, suggestTransfers(b))
        assertEquals(ErrorCode.OUT_OF_RANGE, assertFailsWith<AccountingException> { suggestTransfers(balances(Long.MIN_VALUE, Long.MAX_VALUE, 1)) }.code)
    }
    @Test fun `mixed generated balances preserve money directions and deterministic ordering`() {
        val random = Random(20260916)
        repeat(300) {
            val count = 20
            val negative = random.nextInt(1, count)
            fun partition(parts: Int): List<Long> {
                val cuts = sortedSetOf(0, 10000)
                while (cuts.size < parts + 1) cuts += random.nextInt(1, 10000)
                return cuts.zipWithNext { a, b -> (b-a).toLong() }
            }
            val b = balances(*(partition(negative).map { -it } + partition(count-negative)).toLongArray())
            val before = b.toMap()
            verify(b, suggestTransfers(b)); assertEquals(before, b)
        }
    }
}
