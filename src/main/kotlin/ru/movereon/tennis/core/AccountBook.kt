package ru.movereon.tennis.core

/** A trusted application layer establishes this identity and any representation rights. */
data class Actor(val id: String, val party: ParticipantId? = null) {
    init { checkId(id) }
}

enum class TransferStatus { ACTIVE, UNDER_REVIEW, CANCELLED }
enum class TransferAction { REVIEW, CONFIRM, CANCEL }

sealed interface Command {
    val entityId: String

    data class PostTraining(override val entityId: String, val training: Training, val expectedVersion: Long = 0) : Command
    data class CancelTraining(override val entityId: String, val expectedVersion: Long) : Command
    data class RecordTransfer(override val entityId: String, val from: ParticipantId, val to: ParticipantId, val amount: Long) : Command
    data class ChangeTransfer(override val entityId: String, val expectedVersion: Long, val action: TransferAction) : Command
}

data class Receipt(val sequence: Long, val entityVersion: Long)
data class JournalEvent(
    val sequence: Long,
    val commandId: String,
    val actorId: String,
    val representedParty: ParticipantId?,
    val entityId: String,
    val kind: String,
    val entries: List<BalanceEntry>,
)
data class TransferSnapshot(
    val from: ParticipantId,
    val to: ParticipantId,
    val amount: Long,
    val version: Long,
    val status: TransferStatus,
    val reviewInitiator: String?,
)

internal data class TrainingCheckpoint(val version: Long, val active: Boolean, val entries: List<BalanceEntry>)
internal data class BookCheckpoint(
    val groupId: String,
    val sequence: Long,
    val balances: Map<ParticipantId, Long>,
    val trainings: Map<String, TrainingCheckpoint>,
    val transfers: Map<String, TransferSnapshot>,
)

/**
 * Single-group, in-memory reference aggregate. It is not persistent storage.
 * Group membership, training permissions, dates and Telegram identity verification
 * belong to the application layer. Financial transition rules are enforced here.
 * A storage adapter must persist source, journal, command receipt and notifications
 * within one transaction before claiming durable success.
 */
class AccountBook(val groupId: String) {
    init { checkId(groupId) }

    private data class RecordedTraining(val version: Long, val active: Boolean, val entries: List<BalanceEntry>)
    private data class Processed(val actor: Actor, val command: Command, val receipt: Receipt)
    private val trainings = mutableMapOf<String, RecordedTraining>()
    private val transfers = mutableMapOf<String, TransferSnapshot>()
    private val processed = mutableMapOf<String, Processed>()
    private val events = mutableListOf<JournalEvent>()
    private var currentBalances: Map<ParticipantId, Long> = emptyMap()
    private var sequence = 0L

    // Checkpoints contain current financial state. Durable command receipts and
    // the full journal are kept by the storage adapter, not copied into each checkpoint.
    internal fun checkpoint(): BookCheckpoint = synchronized(this) {
        BookCheckpoint(groupId, sequence, currentBalances.toMap(),
            trainings.mapValues { (_, value) -> TrainingCheckpoint(value.version, value.active, value.entries.toList()) },
            transfers.toMap())
    }

    companion object {
        internal fun fromCheckpoint(state: BookCheckpoint): AccountBook {
            checkAccounting(state.sequence >= 0, ErrorCode.INVALID_INPUT, "Invalid checkpoint sequence")
            val book = AccountBook(state.groupId)
            validateBalances(state.balances)
            val sourceEntries = mutableListOf<BalanceEntry>()
            state.trainings.forEach { (id, training) ->
                checkId(id)
                checkAccounting(training.version in 1..state.sequence, ErrorCode.INVALID_INPUT, "Invalid training version")
                applyEntries(emptyMap(), training.entries)
                if (training.active) sourceEntries += training.entries
                book.trainings[id] = RecordedTraining(training.version, training.active, training.entries.toList())
            }
            state.transfers.forEach { (id, transfer) ->
                checkId(id)
                checkAccounting(transfer.from != transfer.to && transfer.amount > 0 && transfer.version in 1..state.sequence,
                    ErrorCode.INVALID_INPUT, "Invalid transfer checkpoint")
                checkAccounting((transfer.status == TransferStatus.UNDER_REVIEW) == (transfer.reviewInitiator != null),
                    ErrorCode.INVALID_STATE, "Invalid review initiator")
                transfer.reviewInitiator?.let(::checkId)
                if (transfer.status == TransferStatus.ACTIVE) sourceEntries += book.transferEntries(transfer.from, transfer.to, transfer.amount)
                book.transfers[id] = transfer
            }
            val sourceBalances = applyEntries(emptyMap(), sourceEntries).filterValues { it != 0L }
            checkAccounting(sourceBalances == state.balances.filterValues { it != 0L }, ErrorCode.UNBALANCED, "Checkpoint sources and balances differ")
            book.currentBalances = state.balances.toMap()
            book.sequence = state.sequence
            return book
        }
    }

    @Synchronized
    fun balances(): Map<ParticipantId, Long> = currentBalances.toMap()

    @Synchronized
    fun journal(): List<JournalEvent> = events.map { it.copy(entries = it.entries.toList()) }

    @Synchronized
    fun transfer(id: String): TransferSnapshot? = transfers[id]

    @Synchronized
    fun trainingVersion(id: String): Long? = trainings[id]?.version

    @Synchronized
    fun execute(commandId: String, actor: Actor, input: Command): Receipt {
        checkId(commandId)
        checkId(input.entityId)
        val command = when (input) {
            is Command.PostTraining -> input.copy(training = input.training.copy(
                players = input.training.players.toList(), payments = input.training.payments.toList(),
            ))
            else -> input
        }
        processed[commandId]?.let { old ->
            checkAccounting(old.actor == actor && old.command == command, ErrorCode.COMMAND_CONFLICT, "Command ID already used for different input")
            return old.receipt
        }

        // Prepare and validate everything before committing any mutable state.
        var nextTraining: RecordedTraining? = null
        var nextTransfer: TransferSnapshot? = null
        val changes: List<BalanceEntry>
        val version: Long
        val kind: String
        when (command) {
            is Command.PostTraining -> {
                val old = trainings[command.entityId]
                checkVersion(command.expectedVersion, old?.version ?: 0)
                checkAccounting(old == null || old.active, ErrorCode.INVALID_STATE, "Cancelled training cannot be posted again")
                val allocation = calculateTraining(command.training)
                version = nextVersion(old?.version ?: 0)
                changes = (old?.entries?.reversedAmounts() ?: emptyList()) + allocation.entries
                nextTraining = RecordedTraining(version, true, allocation.entries.toList())
                kind = if (old == null) "training_posted" else "training_revised"
            }
            is Command.CancelTraining -> {
                val old = trainings[command.entityId]
                    ?: throw AccountingException(ErrorCode.INVALID_STATE, "Unknown training")
                checkVersion(command.expectedVersion, old.version)
                checkAccounting(old.active, ErrorCode.INVALID_STATE, "Training is already cancelled")
                version = nextVersion(old.version)
                changes = old.entries.reversedAmounts()
                nextTraining = old.copy(version = version, active = false)
                kind = "training_cancelled"
            }
            is Command.RecordTransfer -> {
                checkAccounting(command.entityId !in transfers, ErrorCode.INVALID_STATE, "Transfer ID already exists")
                checkAccounting(command.from != command.to && command.amount > 0, ErrorCode.INVALID_INPUT, "Transfer needs different parties and a positive amount")
                checkParty(actor, command.from, command.to)
                version = 1
                changes = transferEntries(command.from, command.to, command.amount)
                nextTransfer = TransferSnapshot(command.from, command.to, command.amount, version, TransferStatus.ACTIVE, null)
                kind = "transfer_recorded"
            }
            is Command.ChangeTransfer -> {
                val old = transfers[command.entityId]
                    ?: throw AccountingException(ErrorCode.INVALID_STATE, "Unknown transfer")
                checkVersion(command.expectedVersion, old.version)
                checkParty(actor, old.from, old.to)
                version = nextVersion(old.version)
                val original = transferEntries(old.from, old.to, old.amount)
                when (command.action) {
                    TransferAction.REVIEW -> {
                        checkAccounting(old.status == TransferStatus.ACTIVE, ErrorCode.INVALID_STATE, "Only active transfers can be reviewed")
                        changes = original.reversedAmounts()
                        nextTransfer = old.copy(version = version, status = TransferStatus.UNDER_REVIEW, reviewInitiator = actor.id)
                        kind = "transfer_under_review"
                    }
                    TransferAction.CONFIRM, TransferAction.CANCEL -> {
                        checkAccounting(old.status == TransferStatus.UNDER_REVIEW, ErrorCode.INVALID_STATE, "Transfer must be under review")
                        checkAccounting(old.reviewInitiator == actor.id, ErrorCode.FORBIDDEN, "Only the review initiator may resolve it")
                        val confirmed = command.action == TransferAction.CONFIRM
                        changes = if (confirmed) original else emptyList()
                        nextTransfer = old.copy(version = version,
                            status = if (confirmed) TransferStatus.ACTIVE else TransferStatus.CANCELLED,
                            reviewInitiator = null)
                        kind = if (confirmed) "transfer_confirmed" else "transfer_cancelled"
                    }
                }
            }
        }
        val updated = applyEntries(currentBalances, changes)
        val receipt = Receipt(nextVersion(sequence), version)
        val event = JournalEvent(receipt.sequence, commandId, actor.id, actor.party, command.entityId, kind, changes.toList())
        nextTraining?.let { trainings[command.entityId] = it }
        nextTransfer?.let { transfers[command.entityId] = it }
        currentBalances = updated
        sequence = receipt.sequence
        events += event
        processed[commandId] = Processed(actor, command, receipt)
        return receipt
    }

    private fun checkVersion(expected: Long, actual: Long) =
        checkAccounting(expected == actual, ErrorCode.STALE_VERSION, "Expected version $expected, current version $actual")

    private fun nextVersion(value: Long): Long {
        checkAccounting(value < Long.MAX_VALUE, ErrorCode.OUT_OF_RANGE, "Version limit reached")
        return value + 1
    }

    private fun checkParty(actor: Actor, from: ParticipantId, to: ParticipantId) =
        checkAccounting(actor.party == from || actor.party == to, ErrorCode.FORBIDDEN, "Actor must represent a transfer party")

    private fun transferEntries(from: ParticipantId, to: ParticipantId, amount: Long) =
        listOf(BalanceEntry(from, amount), BalanceEntry(to, -amount))
}
