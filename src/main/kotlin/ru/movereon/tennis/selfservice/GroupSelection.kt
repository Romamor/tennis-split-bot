package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.GroupOption
import ru.movereon.tennis.core.ErrorCode
import ru.movereon.tennis.core.checkAccounting

/** Shared eligibility rules for the visible picker and automatic single-group navigation. */
internal object GroupSelection {
    fun choices(options:List<GroupOption>,section:String,hasPolls:(GroupOption)->Boolean={ true })=options.filter {
        when(section) {
            "manage","poll_settings" -> it.admin
            "administrators" -> it.superAdmin
            "polls" -> hasPolls(it)
            else -> true
        }
    }
    fun publication(options:List<GroupOption>,poll:Boolean,enabled:(Long)->Boolean)=
        options.filter { it.canPublish && (!poll || enabled(it.group.id)) }
    fun parent(section:String)=ScreenAction(if(section in setOf("administrators","poll_settings")) "settings" else "menu",0)
    fun destination(section:String,choice:GroupOption):ScreenAction {
        checkAccounting(choice in choices(listOf(choice),section),ErrorCode.FORBIDDEN,"Доступно администратору этой группы")
        return when(section) {
            "poll_settings" -> ScreenAction("poll_settings",choice.group.id)
            "polls" -> ScreenAction("poll_list",choice.group.id)
            "manage" -> ScreenAction("trainings",choice.group.id,option="all")
            "administrators" -> ScreenAction("administrators",choice.group.id)
            else -> ScreenAction("finance",choice.group.id)
        }
    }
}
