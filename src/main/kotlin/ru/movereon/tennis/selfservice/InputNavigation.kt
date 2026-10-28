package ru.movereon.tennis.selfservice

/** All screens which leave input, plus explicit entry points which can replace it. */
internal object InputNavigation {
    val screens=FormerMemberScreens.kinds+NavigationScreens.kinds+TrainingScreens.kinds+PollScreens.kinds+FinanceScreens.kinds+
        setOf("my_trainings","trainings","roster","history","administrators","admin_candidates","admin_person","preview_finish","cancel_confirm","recover_confirm","close_panel")
    val entries=setOf("new","new_poll","default_title","default_time","edit_details","finance_payment_edit",
        "payment_new","finance_send_confirm","pick_account","pick_add_player","add_players","player","ask_paid")
    val submissions=setOf("save_training","save_poll","save_default_title","save_default_time","payment_save","save_players")
    val draftActions=setOf("player","change","reload_attendance","save_attendance","discard_attendance")
    val effects=setOf("poll_close","poll_retry","poll_discard","group_rule_save","poll_setting_save","retry_pin","recover_card","public_page")
    fun continues(action:ScreenAction,form:InputForm?,draft:AttendanceDraft?,group:Long):Boolean=when(action.kind) {
        "payment_new","finance_send_confirm" -> PaymentInput.isForm(form) && form!!.group==action.group && form.adminPayment==(action.option=="admin")
        "add_players" -> form?.kind in setOf("add_players","pick_players") && form?.group==action.group && form.training==action.id
        "player","ask_paid" -> (form==null || form.kind in setOf("paid","attendance")) && group==action.group && draft!=null &&
            (action.kind=="player" || draft.training==action.id && draft.user==(action.user.takeIf { it>0 } ?: draft.user))
        else -> false
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
