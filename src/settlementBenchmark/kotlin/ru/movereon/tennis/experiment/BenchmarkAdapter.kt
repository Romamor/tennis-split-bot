package ru.movereon.tennis.experiment

import ru.movereon.tennis.core.*

/** Same input/output types and financial validation as the production function. */
object BenchmarkAdapter {
    @JvmStatic fun custom(balances: Map<ParticipantId, Long>): List<SuggestedTransfer> {
        validateBalances(balances)
        require(balances.size <= 20 && balances.values.all { it in -1_000_000_000L..1_000_000_000L })
        val ids = balances.keys.sortedBy { it.value }
        val values = ids.map { balances.getValue(it) }.toLongArray()
        return SettlementPrototype.fast(values).map { SuggestedTransfer(ids[it.from()], ids[it.to()], it.amount()) }
    }
}
