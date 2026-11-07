package ru.movereon.tennis.selfservice

/** All screens which leave input, plus explicit entry points which can replace it. */
internal object InputNavigation {
    val screens=ActionRegistry.matching { it.leavesInput }
    val entries=ActionRegistry.matching { it.startsInput }
    val submissions=ActionRegistry.matching { it.submitsInput }
    val draftActions=ActionRegistry.matching { it.attendanceDraftAction }
    val effects=ActionRegistry.matching { it.sideEffect }
    fun continues(action:ScreenAction,form:InputForm?,draft:AttendanceDraft?,group:Long):Boolean=when(ActionRegistry.requireKnown(action.kind).continuation) {
        InputContinuation.PAYMENT -> PaymentInput.isForm(form) && form!!.group==action.group && form.adminPayment==(action.option=="admin")
        InputContinuation.PLAYERS -> form?.kind in setOf("add_players","pick_players") && form?.group==action.group && form.training==action.id
        InputContinuation.PLAYER,InputContinuation.PAID -> (form==null || form.kind in setOf("paid","attendance")) && group==action.group && draft!=null &&
            (action.kind=="player" || draft.training==action.id && draft.user==(action.user.takeIf { it>0 } ?: draft.user))
        InputContinuation.NONE -> false
    }
}

/** Compare entered content, not screen navigation or callback metadata. */
internal fun InputForm.baselineValues()=listOf(title,date,time,amount.toString(),note,declineLabel,pollPhotoId.orEmpty()).joinToString("\u0000")
internal fun InputForm?.hasChanges():Boolean=when {
    this==null || kind in setOf("attendance","paid") -> false
    kind in setOf("add_players","pick_players") -> selectedUsers.isNotEmpty()
    kind=="ready" && training.isEmpty() -> true
    baseline!=null -> baseline!=baselineValues()
    else -> amount>0 || note.isNotBlank()
}
