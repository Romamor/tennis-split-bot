package ru.movereon.tennis.selfservice

import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.core.ErrorCode

internal enum class ActionContext { PRIVATE, ANY_CHAT }
internal enum class ActionBehavior { NAVIGATION, INPUT, SIDE_EFFECT }
internal enum class InputGuard { LEAVE, REPLACE, CONTINUE, SUBMIT, ATTENDANCE, EXIT_CONFIRMATION, BLOCK }
internal enum class InputContinuation { NONE, PAYMENT, PLAYERS, PLAYER, PAID }
internal enum class ActionRoute { SCREEN, NAVIGATION_SCREEN, TRAINING_SCREEN, FINANCE_SCREEN, POLL_SCREEN, FORMER_MEMBER_SCREEN, TRAINING_INPUT, PAYMENT_INPUT, BOT, DELIVERY, RETIRED_FINANCE }

/** UI routing only. Membership, permissions, amounts and command validity belong to the application. */
internal data class ActionSpec(
    val kind:String,
    val context:ActionContext,
    val behavior:ActionBehavior,
    val guard:InputGuard,
    val route:ActionRoute,
    val continuation:InputContinuation=InputContinuation.NONE,
    val allowsFormerMember:Boolean=false,
    val global:Boolean=false
) {
    val privateOnly get()=context==ActionContext.PRIVATE
    val leavesInput get()=guard==InputGuard.LEAVE
    val startsInput get()=guard==InputGuard.REPLACE
    val checksInput get()=leavesInput || startsInput || guard==InputGuard.EXIT_CONFIRMATION
    val submitsInput get()=guard==InputGuard.SUBMIT
    val attendanceDraftAction get()=guard==InputGuard.ATTENDANCE || continuation==InputContinuation.PLAYER
    val sideEffect get()=behavior==ActionBehavior.SIDE_EFFECT
}

/** Stable callback names are declared once, including persisted actions from older versions. */
internal object ActionRegistry {
    private fun screens(vararg names:String,route:ActionRoute=ActionRoute.SCREEN,context:ActionContext=ActionContext.PRIVATE,
                        guard:InputGuard=InputGuard.LEAVE,former:Boolean=false,global:Boolean=false)=
        names.map { ActionSpec(it,context,ActionBehavior.NAVIGATION,guard,route,allowsFormerMember=former,global=global) }
    private fun input(vararg names:String,route:ActionRoute=ActionRoute.BOT,context:ActionContext=ActionContext.PRIVATE,
                      guard:InputGuard=InputGuard.CONTINUE,continuation:InputContinuation=InputContinuation.NONE,global:Boolean=false)=
        names.map { ActionSpec(it,context,ActionBehavior.INPUT,guard,route,continuation,global=global) }
    private fun effects(vararg names:String,route:ActionRoute=ActionRoute.BOT,context:ActionContext=ActionContext.PRIVATE,
                        guard:InputGuard=InputGuard.BLOCK)=
        names.map { ActionSpec(it,context,ActionBehavior.SIDE_EFFECT,guard,route) }

    val all:List<ActionSpec> = buildList {
        addAll(screens("groups","poll_settings",route=ActionRoute.NAVIGATION_SCREEN))
        addAll(screens("menu","settings","training_settings",route=ActionRoute.NAVIGATION_SCREEN,global=true))
        addAll(screens("finance","finance_balance_history",route=ActionRoute.FINANCE_SCREEN,former=true))
        addAll(screens("finance_member_history","finance_balance_members","finance_send","finance_receive","finance_receive_confirm",
            "finance_received","finance_balances","finance_payment","finance_history","finance_group_history","finance_payment_cancel_confirm",route=ActionRoute.FINANCE_SCREEN))
        addAll(screens("my_training",route=ActionRoute.TRAINING_SCREEN,former=true))
        addAll(screens("training_status","add_player_list","exclude_player_list","manage_players",route=ActionRoute.TRAINING_SCREEN))
        addAll(screens("training","public","participation","participation_time","participation_payment",route=ActionRoute.TRAINING_SCREEN,context=ActionContext.ANY_CHAT))
        addAll(screens("poll_list",route=ActionRoute.POLL_SCREEN))
        addAll(screens("poll_detail","poll_close_confirm","poll_discard_confirm","poll_close_recover_confirm",route=ActionRoute.POLL_SCREEN,context=ActionContext.ANY_CHAT))
        addAll(screens("own_group_trainings",route=ActionRoute.FORMER_MEMBER_SCREEN,former=true))
        addAll(screens("my_trainings",global=true))
        addAll(screens("trainings","roster","history","administrators","admin_candidates","admin_person"))
        addAll(screens("preview_finish","cancel_confirm","recover_confirm",context=ActionContext.ANY_CHAT))
        addAll(screens("profile_preview",guard=InputGuard.CONTINUE))
        addAll(screens("leave_confirm",context=ActionContext.ANY_CHAT,guard=InputGuard.CONTINUE))
        addAll(screens("form","exit_confirm",context=ActionContext.ANY_CHAT,guard=InputGuard.CONTINUE))
        addAll(screens("groups_back",route=ActionRoute.BOT))
        addAll(screens("select_group",route=ActionRoute.BOT,context=ActionContext.ANY_CHAT))
        addAll(screens("edit_training",route=ActionRoute.BOT,context=ActionContext.ANY_CHAT))
        addAll(screens("close_panel",route=ActionRoute.DELIVERY,context=ActionContext.ANY_CHAT))
        addAll(screens("group_menu","private_link",route=ActionRoute.DELIVERY,context=ActionContext.ANY_CHAT,guard=InputGuard.CONTINUE))

        addAll(input("new","new_poll","default_title","default_time",route=ActionRoute.TRAINING_INPUT,guard=InputGuard.REPLACE,global=true))
        addAll(input("edit_details",route=ActionRoute.TRAINING_INPUT,guard=InputGuard.REPLACE))
        addAll(input("form_back","form_cancel","form_group_page","form_group_select","form_date_adjust","form_time_adjust",
            "form_next","form_restart","form_photo","form_photo_remove",route=ActionRoute.TRAINING_INPUT,context=ActionContext.ANY_CHAT))
        addAll(effects("save_training","save_poll",route=ActionRoute.TRAINING_INPUT,context=ActionContext.ANY_CHAT,guard=InputGuard.SUBMIT))
        addAll(effects("save_default_title","save_default_time",route=ActionRoute.TRAINING_INPUT,guard=InputGuard.SUBMIT))
        addAll(input("payment_new","finance_send_confirm",route=ActionRoute.PAYMENT_INPUT,guard=InputGuard.REPLACE,continuation=InputContinuation.PAYMENT))
        addAll(input("payment_pick","payment_page","payment_back","payment_cancel","payment_previous","payment_adjust","payment_balance",
            "payment_recommend","payment_round","payment_type",route=ActionRoute.PAYMENT_INPUT))
        addAll(effects("payment_save",route=ActionRoute.PAYMENT_INPUT,guard=InputGuard.SUBMIT))
        addAll(input("finance_payment_edit","pick_account","pick_add_player",guard=InputGuard.REPLACE))
        addAll(input("add_players",guard=InputGuard.REPLACE,continuation=InputContinuation.PLAYERS))
        addAll(input("toggle_player","pick_players"))
        addAll(effects("save_players",guard=InputGuard.SUBMIT))
        addAll(input("player",context=ActionContext.ANY_CHAT,guard=InputGuard.REPLACE,continuation=InputContinuation.PLAYER))
        addAll(input("ask_paid",guard=InputGuard.REPLACE,continuation=InputContinuation.PAID))
        addAll(input("change","reload_attendance",context=ActionContext.ANY_CHAT,guard=InputGuard.ATTENDANCE))
        addAll(effects("save_attendance","discard_attendance",context=ActionContext.ANY_CHAT,guard=InputGuard.ATTENDANCE))
        addAll(input("exit_continue","exit_discard",context=ActionContext.ANY_CHAT,guard=InputGuard.EXIT_CONFIRMATION))

        addAll(effects("finance_payment_cancel_save","finance_send_save","finance_receive_save","set_training_status","add_player","remove_player","set_admin"))
        addAll(effects("participation_change","finish","reopen","cancel","restore",context=ActionContext.ANY_CHAT))
        addAll(effects("group_rule_save","poll_setting_save","poll_retry"))
        addAll(effects("poll_close","poll_close_retry","poll_close_recover","poll_discard","retry_pin","recover_card","public_page",context=ActionContext.ANY_CHAT))
        addAll(effects("poll_publish",route=ActionRoute.DELIVERY,context=ActionContext.ANY_CHAT))
        addAll(screens("edit_redirect",route=ActionRoute.DELIVERY,context=ActionContext.ANY_CHAT,guard=InputGuard.CONTINUE))

        // These callbacks only redirect to the current finance screen; no retired command is executed.
        addAll(screens("finance_debtors","debts","balances","settled","transfers","transfer","transfer_people","transfer_direction",
            "transfer_amount","suggested_transfer","save_transfer","transfer_date","transfer_note","edit_transfer_amount","save_transfer_amount",
            "review_transfer","confirm_transfer","cancel_transfer","transfer_history",route=ActionRoute.RETIRED_FINANCE,context=ActionContext.ANY_CHAT))
    }
    private val byKind=all.associateBy { it.kind }.also { check(it.size==all.size) { "Duplicate action in registry" } }
    fun requireKnown(kind:String):ActionSpec=byKind[kind]
        ?: throw AccountingException(ErrorCode.INVALID_INPUT,"Кнопка больше не поддерживается. Открой меню заново.")
    fun kinds(route:ActionRoute):Set<String> = all.filter { it.route==route }.mapTo(linkedSetOf()) { it.kind }
    fun matching(predicate:(ActionSpec)->Boolean):Set<String> = all.filter(predicate).mapTo(linkedSetOf()) { it.kind }
}
