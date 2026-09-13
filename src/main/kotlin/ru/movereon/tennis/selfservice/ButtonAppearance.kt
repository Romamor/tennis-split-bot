package ru.movereon.tennis.selfservice

import ru.movereon.tennis.telegram.*

/** Telegram chooses the actual shades and geometry; these are semantic API styles. */
internal object ButtonAppearance {
    fun keyboard(rows:List<List<TgButton>>,actions:Map<TgButton,ScreenAction> = emptyMap()):TgKeyboard = TgKeyboard(rows.map { row ->
        row.map { button ->
            val action=actions[button]
            val label=button.text
            button.copy(
                text=if(row.size==1 && !hasIcon(label)) "${icon(action,label)} $label" else label,
                style=button.style ?: when {
                    label=="Открыть" || label.startsWith("Открыть меню") || label.startsWith("Присоединиться") -> "primary"
                    label=="Не участвую" -> "danger"
                    else -> null
                })
        }
    })
    private fun hasIcon(text:String)=text.isNotEmpty() && Character.getType(text.codePointAt(0))==Character.OTHER_SYMBOL.toInt()
    private fun icon(action:ScreenAction?,label:String):String {
        if(label.startsWith("Назад") || label.startsWith("К составу")) return "⬅️"
        if(label.startsWith("Закрыть") || label.startsWith("Отмена")) return "✖️"
        if(label=="Открыть" || label.startsWith("Присоединиться")) return "🏓"
        if(label=="Не участвую") return "🚪"
        if(label.startsWith("Открыть профиль")) return "🔗"
        if(label.startsWith("Оплата") || label=="Другая сумма") return "💵"
        if(label.startsWith("Время")) return "🕒"
        return when(action?.kind) {
            "menu","group_menu" -> "🏠"
            "my_training","training","participation" -> "🏓"
            "select_group","form_group_select","groups" -> "👥"
            "edit_training","edit_details","form_restart" -> "✏️"
            "training_status","set_training_status" -> when(action.option) { "CANCELLED"->"🚫";"CLOSED"->"✅";"OPEN"->"🔓";else->"🔄" }
            "history","transfer_history","finance_history" -> "📜"
            "finance","finance_balances","balances","settled" -> "💰"
            "finance_send","finance_send_confirm","finance_send_save","payment_new","finance_payment" -> "💸"
            "finance_receive","finance_receive_confirm","finance_receive_save" -> "📥"
            "payment_save","save_training","save_attendance","save_players","save_default_title","save_default_time" -> "✅"
            "payment_previous" -> "📜"
            "payment_back","form_back" -> "⬅️"
            "payment_cancel","form_cancel","close_panel" -> "✖️"
            "payment_pick","player","change","admin_person","add_player","remove_player" -> "👤"
            "manage_players","roster","add_players","add_player_list","exclude_player_list" -> "👥"
            "participation_change" -> if(action.option=="ADJUST_GUESTS") "👥" else "🏓"
            "administrators","admin_candidates","set_admin" -> "🛡️"
            "form_next" -> if(label.startsWith("Оставить")) "📝" else "➡️"
            "training_settings","settings" -> "⚙️"
            "default_title" -> "📝"
            "default_time" -> "🕒"
            "exit_continue" -> "✏️"
            "exit_discard" -> "🗑️"
            "recover_confirm","recover_card","retry_pin" -> "📌"
            else -> if(buttonLink(label)) "🔗" else "➡️"
        }
    }
    private fun buttonLink(label:String)=label.startsWith("Открыть") || label.contains("Telegram")
}
