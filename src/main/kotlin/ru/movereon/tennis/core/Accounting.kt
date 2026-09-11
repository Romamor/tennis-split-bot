package ru.movereon.tennis.core

import java.math.BigInteger

enum class ErrorCode { INVALID_INPUT, OUT_OF_RANGE, UNBALANCED, STALE_VERSION, COMMAND_CONFLICT, INVALID_STATE, FORBIDDEN }

class AccountingException(val code: ErrorCode, message: String) : IllegalArgumentException(message)

internal fun checkAccounting(condition: Boolean, code: ErrorCode, message: String) {
    if (!condition) throw AccountingException(code, message)
}

internal fun checkId(id: String) = checkAccounting(
    id.isNotBlank() && id == id.trim(), ErrorCode.INVALID_INPUT, "Identifier must be non-blank and trimmed",
)

@JvmInline
value class ParticipantId(val value: String) {
    init { checkId(value) }
    override fun toString(): String = value
}

/** All monetary values are whole rubles. No floating-point arithmetic is used. */
fun parseAmount(text: String): Long {
    val normalized = text.trim()
    checkAccounting(normalized.isNotEmpty() && normalized.all { it in '0'..'9' }, ErrorCode.INVALID_INPUT, "Use whole rubles")
    val amount = normalized.toLongOrNull()
        ?: throw AccountingException(ErrorCode.OUT_OF_RANGE, "Amount is too large")
    checkAccounting(amount > 0, ErrorCode.INVALID_INPUT, "Amount must be positive")
    return amount
}

data class PlayerSlot(val participant: ParticipantId, val minutes: Long, val plusOne: Boolean = false)
data class ExpensePayment(val participant: ParticipantId, val amount: Long)
data class Training(val players: List<PlayerSlot>, val payments: List<ExpensePayment>)
data class BalanceEntry(val participant: ParticipantId, val amount: Long)
data class Allocation(
    val total: Long,
    val slotShares: List<Long>,
    val participantShares: Map<ParticipantId, Long>,
    val entries: List<BalanceEntry>,
)

private val MAX_AMOUNT = BigInteger.valueOf(Long.MAX_VALUE)

internal fun BigInteger.toAmount(): Long {
    checkAccounting(abs() <= MAX_AMOUNT, ErrorCode.OUT_OF_RANGE, "Amount is outside the supported symmetric Long range")
    return toLong()
}

private data class TrainingTotals(val minutes: BigInteger, val paid: Map<ParticipantId, Long>, val cost: BigInteger)

/** An unfinished draft may omit players or payments; supplied values must be valid. */
fun validateDraftTraining(training: Training) { validateTraining(training, complete = false) }

private fun validateTraining(training: Training, complete: Boolean): TrainingTotals {
    if (complete) checkAccounting(training.players.isNotEmpty() && training.payments.isNotEmpty(), ErrorCode.INVALID_INPUT, "Players and payments are required")
    val mainPlayers = mutableSetOf<ParticipantId>()
    val guests = mutableSetOf<ParticipantId>()
    training.players.forEach { player ->
        checkAccounting(player.minutes > 0, ErrorCode.INVALID_INPUT, "Minutes must be positive")
        if (player.plusOne) guests.add(player.participant)
        else checkAccounting(mainPlayers.add(player.participant), ErrorCode.INVALID_INPUT, "Duplicate player slot")
    }
    checkAccounting(mainPlayers.containsAll(guests), ErrorCode.INVALID_INPUT, "A plus-one needs an inviting player")
    val minutes = training.players.fold(BigInteger.ZERO) { sum, player -> sum + player.minutes.toBigInteger() }
    minutes.toAmount()

    val paid = linkedMapOf<ParticipantId, Long>()
    training.payments.forEach { payment ->
        checkAccounting(payment.amount > 0, ErrorCode.INVALID_INPUT, "Payment must be positive")
        checkAccounting(payment.participant !in paid, ErrorCode.INVALID_INPUT, "Use one total payment per payer")
        paid[payment.participant] = payment.amount
    }
    val cost = paid.values.fold(BigInteger.ZERO) { sum, amount -> sum + amount.toBigInteger() }
    cost.toAmount()
    return TrainingTotals(minutes, paid, cost)
}

/** Input order is the persisted rounding tie-break order, including plus-ones. */
fun calculateTraining(training: Training): Allocation {
    val (minutes, paid, cost) = validateTraining(training, complete = true)
    val total = cost.toAmount()
    val quotients = training.players.map { (cost * it.minutes.toBigInteger()).divideAndRemainder(minutes) }
    val shares = quotients.map { it[0].toLong() }.toMutableList()
    val remainder = (total - shares.sum()).toInt() // Strictly less than the number of slots.
    val order = training.players.indices.sortedWith(compareByDescending<Int> { quotients[it][1] }.thenBy { it })
    order.take(remainder).forEach { shares[it]++ }
    val participantShares = linkedMapOf<ParticipantId, Long>()
    training.players.forEachIndexed { i, player ->
        participantShares[player.participant] = participantShares.getOrDefault(player.participant, 0) + shares[i]
    }
    val entries = (paid.keys + participantShares.keys).sortedBy { it.value }.map { id ->
        BalanceEntry(id, paid.getOrDefault(id, 0) - participantShares.getOrDefault(id, 0))
    }
    return Allocation(total, shares.toList(), participantShares.toMap(), entries)
}

fun validateBalances(balances: Map<ParticipantId, Long>) {
    balances.values.forEach { it.toBigInteger().toAmount() }
    checkAccounting(balances.values.fold(BigInteger.ZERO) { sum, amount -> sum + amount.toBigInteger() } == BigInteger.ZERO,
        ErrorCode.UNBALANCED, "Balances must sum to zero")
}

/** Applies one balanced batch atomically, returning a copy without mutating input. */
fun applyEntries(balances: Map<ParticipantId, Long>, entries: List<BalanceEntry>): Map<ParticipantId, Long> {
    validateBalances(balances)
    entries.forEach { it.amount.toBigInteger().toAmount() }
    checkAccounting(entries.fold(BigInteger.ZERO) { sum, entry -> sum + entry.amount.toBigInteger() } == BigInteger.ZERO,
        ErrorCode.UNBALANCED, "An operation must sum to zero")
    val changes = entries.groupBy { it.participant }.mapValues { (_, values) ->
        values.fold(BigInteger.ZERO) { sum, entry -> sum + entry.amount.toBigInteger() }
    }
    val result = balances.toMutableMap()
    changes.forEach { (id, amount) -> result[id] = (balances.getOrDefault(id, 0).toBigInteger() + amount).toAmount() }
    return result.toMap()
}

internal fun List<BalanceEntry>.reversedAmounts(): List<BalanceEntry> = map { it.copy(amount = -it.amount) }

data class SuggestedTransfer(val from: ParticipantId, val to: ParticipantId, val amount: Long)

/** Greedy matching is deterministic, but does not promise the fewest possible transfers. */
fun suggestTransfers(balances: Map<ParticipantId, Long>): List<SuggestedTransfer> {
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
    return result.toList()
}
