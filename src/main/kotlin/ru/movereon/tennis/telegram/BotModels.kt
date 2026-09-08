package ru.movereon.tennis.telegram

import kotlinx.serialization.Serializable
import ru.movereon.tennis.application.DraftContent
import ru.movereon.tennis.application.WorkflowCommand
import ru.movereon.tennis.application.ParticipantProfile

@Serializable data class TransferForm(val id: String, val from: String? = null, val to: String? = null,
    val amount: Long? = null, val date: String, val note: String? = null, val represented: String? = null)

/** Opaque, user-bound database tokens carry this payload; callbacks never accept raw commands. */
@Serializable data class BotAction(
    val kind: String,
    val editorId: String? = null,
    val editorVersion: Long? = null,
    val value: String? = null,
    val inactiveOnly: Boolean = false,
    val entity: String? = null,
    val version: Long? = null,
    val participant: String? = null,
    val field: String? = null,
    val guest: Boolean = false,
    val page: Int = 0,
    val showAll: Boolean = false,
    val command: WorkflowCommand? = null,
    val draft: DraftContent? = null,
    val form: TransferForm? = null,
    val back: String? = null,
    val represented: String? = null,
)
@Serializable data class PendingInput(val token: String, val action: BotAction, val promptId: Long)
data class BotGroup(val id: String, val chatId: Long, val title: String)
data class BotSession(val userId: Long, val groupId: String?, val panelId: Long?, val input: PendingInput?)
data class SavedAction(val token: String, val userId: Long, val groupId: String, val action: BotAction)
data class SavedLink(val token: String, val groupId: String, val action: BotAction)
data class SavedPlan(val userId: Long, val groupId: String, val token: String, val action: BotAction)
data class Delivery(val key: String, val groupId: String?, val chatId: Long, val messageId: Long?, val status: String)
data class Screen(val text: String, val buttons: List<List<Pair<String, BotAction>>> = emptyList())

internal fun participantLabels(profiles: List<ParticipantProfile>): Map<String,String> = profiles.groupBy { it.name }.values.flatMap { matches ->
    matches.sortedBy { it.id }.mapIndexed { index, p -> p.id to if(matches.size==1) p.name else "${p.name} (${index+1})" }
}.toMap()

/** Older records may contain arbitrary minutes; display hours without changing their calculation. */
internal fun hoursLabel(minutes: Long): String {
    val hours = minutes.toBigDecimal().divide(60.toBigDecimal(),2,java.math.RoundingMode.HALF_UP)
    val approximate = hours.multiply(60.toBigDecimal()).compareTo(minutes.toBigDecimal()) != 0
    return (if(approximate) "≈" else "") + hours.stripTrailingZeros().toPlainString().replace('.',',') + " ч"
}

@Serializable data class DraftEditor(
    val id: String, val groupId: String, val draftId: String,
    val baseline: ru.movereon.tennis.application.TrainingDraft,
    val content: DraftContent, val order: List<String>,
    val remembered: List<ru.movereon.tennis.application.PlayerInput> = emptyList(),
    val revision: Long = 1, val lastUpdate: Long? = null,
) {
    val dirty: Boolean get() = baseline.version == 0L || content != baseline.content
    fun view() = baseline.copy(content=content,status=if(content != baseline.content && baseline.financialVersion > 0)
        ru.movereon.tennis.application.DraftStatus.EDITING else baseline.status)
}
