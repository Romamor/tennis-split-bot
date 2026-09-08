package ru.movereon.tennis.application

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.movereon.tennis.core.*

/** Must be supplied by a trusted adapter after verifying current chat membership. */
data class VerifiedGroupMember(val groupId: String, val userId: Long, val isTelegramAdmin: Boolean = false) {
    init {
        checkId(groupId)
        checkAccounting(userId > 0, ErrorCode.INVALID_INPUT, "Telegram user ID must be positive")
    }
}

/** A trusted adapter has separately verified that this user is no longer in the chat. */
data class VerifiedAbsentMember(val groupId: String, val userId: Long) {
    init { checkId(groupId); require(userId > 0) }
}

@Serializable data class PlayerInput(val participantId: String, val minutes: Long, val plusOne: Boolean = false)
@Serializable data class PaymentInput(val participantId: String, val amount: Long)
@Serializable data class DraftContent(val date: String, val players: List<PlayerInput> = emptyList(), val payments: List<PaymentInput> = emptyList()) {
    fun training(): Training = Training(players.map { PlayerSlot(ParticipantId(it.participantId), it.minutes, it.plusOne) },
        payments.map { ExpensePayment(ParticipantId(it.participantId), it.amount) })

    fun withPlusOne(participantId: String, enabled: Boolean = true): DraftContent {
        val main = players.singleOrNull { it.participantId == participantId && !it.plusOne }
            ?: throw AccountingException(ErrorCode.INVALID_INPUT, "Inviting participant is not playing")
        if (!enabled) return copy(players = players.filterNot { it.participantId == participantId && it.plusOne })
        if (players.any { it.participantId == participantId && it.plusOne }) return this
        return copy(players = players + PlayerInput(participantId, main.minutes, true))
    }
}

@Serializable data class ParticipantProfile(val id: String, val name: String, val telegramUserId: Long?, val active: Boolean, val version: Long)
data class ParticipantBalance(val participant: ParticipantProfile, val balance: Long)
@Serializable enum class DraftStatus { DRAFT, POSTED, EDITING, CANCELLED }
@Serializable data class TrainingDraft(val id: String, val createdBy: Long, val version: Long, val status: DraftStatus,
    val financialVersion: Long, val content: DraftContent, val publishedContent: DraftContent?)
@Serializable data class WorkflowReceipt(val auditId: Long, val entityVersion: Long, val financialSequence: Long? = null,
    val financialVersion: Long? = null)
data class WorkflowAudit(val id: Long, val actorUserId: Long, val kind: String, val entityId: String,
    val beforeJson: String?, val afterJson: String, val recordedAt: String)
@Serializable enum class TransferIntent { REVIEW, CONFIRM, CANCEL }

@Serializable sealed interface WorkflowCommand {
    @Serializable @SerialName("create_group") data class CreateGroup(val timeZone: String) : WorkflowCommand
    @Serializable @SerialName("add_participant") data class AddParticipant(val id: String, val name: String) : WorkflowCommand
    @Serializable @SerialName("rename_participant") data class RenameParticipant(val id: String, val expectedVersion: Long, val name: String) : WorkflowCommand
    @Serializable @SerialName("link_self") data class LinkSelf(val id: String, val expectedVersion: Long) : WorkflowCommand
    @Serializable @SerialName("correct_link") data class CorrectLink(val id: String, val expectedVersion: Long, val userId: Long?) : WorkflowCommand
    @Serializable @SerialName("set_active") data class SetActive(val id: String, val expectedVersion: Long, val active: Boolean) : WorkflowCommand
    @Serializable @SerialName("appoint_organizer") data class AppointOrganizer(val userId: Long) : WorkflowCommand
    @Serializable @SerialName("create_draft") data class CreateDraft(val id: String, val date: String) : WorkflowCommand
    @Serializable @SerialName("save_draft") data class SaveDraft(val id: String, val expectedVersion: Long, val content: DraftContent) : WorkflowCommand
    @Serializable @SerialName("commit_draft") data class CommitDraft(val id: String, val expectedVersion: Long, val content: DraftContent, val post: Boolean = false) : WorkflowCommand
    @Serializable @SerialName("post_draft") data class PostDraft(val id: String, val expectedVersion: Long) : WorkflowCommand
    @Serializable @SerialName("discard_changes") data class DiscardChanges(val id: String, val expectedVersion: Long) : WorkflowCommand
    @Serializable @SerialName("cancel_draft") data class CancelDraft(val id: String, val expectedVersion: Long) : WorkflowCommand
    @Serializable @SerialName("record_transfer") data class RecordTransfer(val id: String, val from: String, val to: String,
        val amount: Long, val date: String, val note: String? = null, val onBehalfOf: String? = null,
        val allowSimilar: Boolean = false) : WorkflowCommand
    @Serializable @SerialName("change_transfer") data class ChangeTransfer(val id: String, val expectedVersion: Long,
        val action: TransferIntent, val onBehalfOf: String? = null) : WorkflowCommand
}

class SimilarTransferFound(val transferIds: List<String>) : IllegalStateException("A similar transfer is already recorded")
