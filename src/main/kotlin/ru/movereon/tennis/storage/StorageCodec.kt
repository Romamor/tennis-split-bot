package ru.movereon.tennis.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.core.*
import java.time.LocalDate

/** Optional source metadata; application-level date and membership rules come later. */
data class OperationDetails(val occurredOn: LocalDate? = null, val note: String? = null)

@Serializable private data class StoredPlayer(val participant: String, val minutes: Long, val plusOne: Boolean)
@Serializable private data class StoredPayment(val participant: String, val amount: Long)
@Serializable private data class StoredEntry(val participant: String, val amount: Long)
@Serializable private data class StoredTraining(val version: Long, val active: Boolean, val entries: List<StoredEntry>)
@Serializable private data class StoredTransfer(
    val from: String, val to: String, val amount: Long, val version: Long, val status: String, val reviewInitiator: String?,
)
@Serializable private data class StoredCheckpoint(
    val formatVersion: Int = 1,
    val groupId: String,
    val sequence: Long,
    val balances: Map<String, Long>,
    val trainings: Map<String, StoredTraining>,
    val transfers: Map<String, StoredTransfer>,
)
@Serializable private data class StoredCommand(
    val formatVersion: Int = 1,
    val kind: String,
    val entityId: String,
    val expectedVersion: Long? = null,
    val players: List<StoredPlayer>? = null,
    val payments: List<StoredPayment>? = null,
    val from: String? = null,
    val to: String? = null,
    val amount: Long? = null,
    val action: String? = null,
)
@Serializable private data class StoredDetails(val occurredOn: String?, val note: String?)

/** Storage DTOs keep serialization dependencies out of the accounting core. */
internal object StorageCodec {
    private val json = Json { encodeDefaults = true }

    fun encodeCommand(command: Command): String = json.encodeToString(when (command) {
        is Command.PostTraining -> StoredCommand(kind = "post_training", entityId = command.entityId,
            expectedVersion = command.expectedVersion,
            players = command.training.players.map { StoredPlayer(it.participant.value, it.minutes, it.plusOne) },
            payments = command.training.payments.map { StoredPayment(it.participant.value, it.amount) })
        is Command.CancelTraining -> StoredCommand(kind = "cancel_training", entityId = command.entityId, expectedVersion = command.expectedVersion)
        is Command.RecordTransfer -> StoredCommand(kind = "record_transfer", entityId = command.entityId,
            from = command.from.value, to = command.to.value, amount = command.amount)
        is Command.ChangeTransfer -> StoredCommand(kind = "change_transfer", entityId = command.entityId,
            expectedVersion = command.expectedVersion, action = command.action.name)
    })

    fun decodeCommand(text: String): Command {
        val command = json.decodeFromString<StoredCommand>(text)
        require(command.formatVersion == 1) { "Unsupported command format" }
        return when (command.kind) {
            "post_training" -> Command.PostTraining(command.entityId, Training(
                requireNotNull(command.players).map { PlayerSlot(ParticipantId(it.participant), it.minutes, it.plusOne) },
                requireNotNull(command.payments).map { ExpensePayment(ParticipantId(it.participant), it.amount) },
            ), requireNotNull(command.expectedVersion))
            "cancel_training" -> Command.CancelTraining(command.entityId, requireNotNull(command.expectedVersion))
            "record_transfer" -> Command.RecordTransfer(command.entityId, ParticipantId(requireNotNull(command.from)),
                ParticipantId(requireNotNull(command.to)), requireNotNull(command.amount))
            "change_transfer" -> Command.ChangeTransfer(command.entityId, requireNotNull(command.expectedVersion),
                TransferAction.valueOf(requireNotNull(command.action)))
            else -> error("Unsupported stored command: ${command.kind}")
        }
    }

    fun encodeDetails(details: OperationDetails): String = json.encodeToString(StoredDetails(details.occurredOn?.toString(), details.note))
    fun decodeDetails(text: String): OperationDetails = json.decodeFromString<StoredDetails>(text).let {
        OperationDetails(it.occurredOn?.let(LocalDate::parse), it.note)
    }

    fun encodeCheckpoint(state: BookCheckpoint): String = json.encodeToString(StoredCheckpoint(
        groupId = state.groupId, sequence = state.sequence,
        balances = state.balances.mapKeys { it.key.value },
        trainings = state.trainings.mapValues { (_, training) -> StoredTraining(training.version, training.active,
            training.entries.map { StoredEntry(it.participant.value, it.amount) }) },
        transfers = state.transfers.mapValues { (_, transfer) -> StoredTransfer(transfer.from.value, transfer.to.value,
            transfer.amount, transfer.version, transfer.status.name, transfer.reviewInitiator) },
    ))

    fun decodeCheckpoint(text: String): BookCheckpoint {
        val state = json.decodeFromString<StoredCheckpoint>(text)
        require(state.formatVersion == 1) { "Unsupported checkpoint format" }
        return BookCheckpoint(state.groupId, state.sequence,
            state.balances.mapKeys { ParticipantId(it.key) },
            state.trainings.mapValues { (_, training) -> TrainingCheckpoint(training.version, training.active,
                training.entries.map { BalanceEntry(ParticipantId(it.participant), it.amount) }) },
            state.transfers.mapValues { (_, transfer) -> TransferSnapshot(ParticipantId(transfer.from), ParticipantId(transfer.to),
                transfer.amount, transfer.version, TransferStatus.valueOf(transfer.status), transfer.reviewInitiator) })
    }
}
