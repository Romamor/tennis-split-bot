package ru.movereon.tennis.experiment

import ru.movereon.tennis.core.*

/** Same input/output types and financial validation as the production function. */
object BenchmarkAdapter {
    /** Frozen pre-change implementation: historical measurements remain reproducible. */
    @JvmStatic fun previous(balances: Map<ParticipantId, Long>): List<SuggestedTransfer> {
        validateBalances(balances)
        val debts = balances.filterValues { it < 0 }.mapValues { -it.value }.toMutableMap()
        val credits = balances.filterValues { it > 0 }.toMutableMap()
        val result = mutableListOf<SuggestedTransfer>()
        val order = compareByDescending<Map.Entry<ParticipantId, Long>> { it.value }.thenBy { it.key.value }
        while (debts.isNotEmpty() && credits.isNotEmpty()) {
            val debt = debts.entries.minWith(order)
            val credit = credits.entries.minWith(order)
            val amount = minOf(debt.value, credit.value)
            result += SuggestedTransfer(debt.key, credit.key, amount)
            debts[debt.key] = debt.value - amount
            credits[credit.key] = credit.value - amount
            if (debts[debt.key] == 0L) debts.remove(debt.key)
            if (credits[credit.key] == 0L) credits.remove(credit.key)
        }
        return result
    }
    @JvmStatic fun custom(balances: Map<ParticipantId, Long>): List<SuggestedTransfer> {
        validateBalances(balances)
        require(balances.size <= 20 && balances.values.all { it in -1_000_000_000L..1_000_000_000L })
        val ids = balances.keys.sortedBy { it.value }
        val values = ids.map { balances.getValue(it) }.toLongArray()
        return SettlementPrototype.fast(values).map { SuggestedTransfer(ids[it.from()], ids[it.to()], it.amount()) }
    }
}
