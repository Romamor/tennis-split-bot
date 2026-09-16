package ru.movereon.tennis.core

/** Ten fixed O(n²) strategies, O(n) space. No search limits or external solver. */
internal object DirectSettlement {
    private enum class Strategy {
        LARGEST_SENDER, SMALLEST_SENDER, SENDER_ORDER, FIT_RECEIVER,
        LARGEST_RECEIVER, SMALLEST_RECEIVER,
    }
    private data class Transfer(val from: Int, val to: Int, val amount: Long)
    private data class Plan(val transfers: List<Transfer>, val maximumOutgoing: Int)

    fun suggest(balances: Map<ParticipantId, Long>): List<SuggestedTransfer> {
        val ids = balances.filterValues { it != 0L }.keys.sortedBy { it.value }
        if (ids.isEmpty()) return emptyList()
        val amounts = LongArray(ids.size) { balances.getValue(ids[it]) }
        val senders = amounts.indices.filter { amounts[it] < 0 }
        val plans = mutableListOf(build(amounts, Strategy.LARGEST_SENDER, 0, matchFirst = false))
        for (strategy in Strategy.entries) {
            val attempts = if (strategy == Strategy.SENDER_ORDER) 4 else 1
            repeat(attempts) { step ->
                plans += build(amounts, strategy, senders[step * senders.size / 4], matchFirst = true)
            }
        }
        val fewest = plans.minOf { it.transfers.size }
        val chosen = plans.filter { it.transfers.size <= fewest + 1 }
            .minWith(compareBy<Plan> { it.maximumOutgoing }.thenBy { it.transfers.size })
        return chosen.transfers.map { SuggestedTransfer(ids[it.from], ids[it.to], it.amount) }
    }

    private fun build(original: LongArray, strategy: Strategy, offset: Int, matchFirst: Boolean): Plan {
        val remaining = original.copyOf()
        val outgoing = IntArray(original.size)
        val transfers = mutableListOf<Transfer>()
        fun pay(from: Int, to: Int) {
            // Each original amount is in the symmetric Long range; only reduce magnitudes.
            val amount = minOf(-remaining[from], remaining[to])
            transfers += Transfer(from, to, amount)
            remaining[from] += amount
            remaining[to] -= amount
            outgoing[from]++
        }
        // Run once, not after every payment: this pass is O(n²).
        if (matchFirst) for (from in remaining.indices) if (remaining[from] < 0) {
            for (to in remaining.indices) if (remaining[to] == -remaining[from]) {
                pay(from, to)
                break
            }
        }
        while (true) {
            var from = -1
            var to = -1
            if (strategy == Strategy.LARGEST_RECEIVER || strategy == Strategy.SMALLEST_RECEIVER) {
                for (i in remaining.indices) if (remaining[i] > 0 && (to < 0 ||
                    if (strategy == Strategy.LARGEST_RECEIVER) remaining[i] > remaining[to] else remaining[i] < remaining[to])) to = i
                if (to >= 0) for (i in remaining.indices) if (remaining[i] < 0 &&
                    (from < 0 || betterFit(-remaining[i], -remaining[from], remaining[to]))) from = i
            }
            if (from < 0) for (step in remaining.indices) {
                val i = (step + offset) % remaining.size
                if (remaining[i] >= 0) continue
                if (from < 0 || when (strategy) {
                    Strategy.SMALLEST_SENDER -> remaining[i] > remaining[from]
                    Strategy.SENDER_ORDER -> false
                    else -> remaining[i] < remaining[from]
                }) from = i
            }
            if (from < 0) break
            if (to < 0) for (i in remaining.indices) if (remaining[i] > 0 && (to < 0 ||
                if (strategy == Strategy.FIT_RECEIVER) betterFit(remaining[i], remaining[to], -remaining[from])
                else remaining[i] > remaining[to])) to = i
            check(to >= 0) { "Balanced input must have a receiver" }
            pay(from, to)
        }
        return Plan(transfers, outgoing.maxOrNull() ?: 0)
    }

    /** Smallest capacity that covers the need; otherwise the largest available capacity. */
    private fun betterFit(candidate: Long, current: Long, need: Long): Boolean = when {
        candidate >= need && current >= need -> candidate < current
        candidate >= need -> true
        current >= need -> false
        else -> candidate > current
    }
}
