package ru.movereon.tennis.telegram

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

/** Private input state; no shared draft, audit event or balance changes here. */
class DraftEditing(private val service: GroupService,private val state: TelegramStore) {
    fun open(member: VerifiedGroupMember,id: String,token: String,date: String? = null): DraftEditor {
        state.editors(member.userId,member.groupId).firstOrNull { it.draftId==id }?.let {
            if(it.dirty || service.draft(member,id).version==it.baseline.version) return it
            state.closeEditor(member.userId,member.groupId,it.id)
        }
        val baseline = if(date == null) service.draft(member,id) else
            service.drafts(member,true).firstOrNull { it.id==id } ?: TrainingDraft(id,member.userId,0,DraftStatus.DRAFT,0,DraftContent(date),null)
        return state.openEditor(member.userId,DraftEditor("e_$token",member.groupId,id,baseline,baseline.content,service.attendanceOrder(member)))
    }
    fun get(member: VerifiedGroupMember,action: BotAction): DraftEditor =
        state.editor(member.userId,member.groupId,requireNotNull(action.editorId)) ?: throw NoSuchElementException("Editor is closed")

    fun commit(member: VerifiedGroupMember,action: BotAction): WorkflowCommand.CommitDraft {
        val editor = get(member,action)
        checkAccounting(editor.revision==action.editorVersion,ErrorCode.STALE_VERSION,"Editor changed")
        return WorkflowCommand.CommitDraft(editor.draftId,editor.baseline.version,editor.content,action.field=="post")
    }

    fun change(member: VerifiedGroupMember,action: BotAction,updateId: Long) = state.edit(member.userId,member.groupId,
        requireNotNull(action.editorId),requireNotNull(action.editorVersion),updateId) { editor ->
        var remembered = editor.remembered
        val content = editor.content
        val id = action.participant
        fun restore(playerId: String) = remembered.filter { it.participantId==playerId }.ifEmpty { listOf(PlayerInput(playerId,60)) }
        val changed = when(action.field) {
            "toggle_player", "remove_player" -> {
                requireNotNull(id)
                val old = content.players.filter { it.participantId==id }
                if(old.isNotEmpty()) {
                    remembered = remembered.filterNot { it.participantId==id } + old
                    content.copy(players=content.players.filterNot { it.participantId==id })
                } else content.copy(players=content.players+restore(id))
            }
            "all_players" -> {
                val ids = service.participants(member,true).filter { it.participant.active != action.inactiveOnly }.map { it.participant.id }
                val chosen = content.players.map { it.participantId }.toSet()
                content.copy(players=content.players+ids.filterNot { it in chosen }.flatMap(::restore))
            }
            "clear_players" -> {
                val chosen = content.players.map { it.participantId }.toSet()
                remembered = remembered.filterNot { it.participantId in chosen } + content.players
                content.copy(players=emptyList())
            }
            "previous_players" -> {
                val previous = if(action.value!=null) service.draft(member,action.value) else
                    service.drafts(member,true).filter { it.status!=DraftStatus.CANCELLED && it.id!=editor.draftId && it.publishedContent!=null }
                        .maxWithOrNull(compareBy<TrainingDraft> { it.publishedContent!!.date }.thenBy { it.id })
                checkAccounting(previous?.publishedContent != null && previous.status!=DraftStatus.CANCELLED,ErrorCode.INVALID_STATE,"No previous training")
                checkAccounting(action.version==null || previous?.version==action.version,ErrorCode.STALE_VERSION,"Previous training changed")
                content.copy(players=requireNotNull(previous?.publishedContent).players)
            }

            "all_minutes", "minutes" -> {
                val minutes = parseAmount(requireNotNull(action.value))
                content.copy(players=content.players.map {
                    if(action.field=="all_minutes" || it.participantId==id && it.plusOne==action.guest) it.copy(minutes=minutes) else it
                })
            }
            "guest" -> content.withPlusOne(requireNotNull(id),content.players.none { it.participantId==id && it.plusOne })
            "payment" -> content.copy(payments=content.payments.filterNot { it.participantId==id } +
                if(action.value=="0") emptyList() else listOf(PaymentInput(requireNotNull(id),parseAmount(requireNotNull(action.value)))))
            "draft_date" -> content.copy(date=requireNotNull(action.value))
            else -> throw AccountingException(ErrorCode.INVALID_INPUT,"Unknown editor action")
        }
        editor.copy(content=changed,remembered=remembered)
    }
}
