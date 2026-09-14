package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** Navigation and group preferences; shares the ordinary keyboard builder and output limits. */
internal class NavigationScreens(private val service:SettlementService,private val polls:TrainingPolls) {
    fun render(layout:ScreenLayout,a:Access?,groupOptions:List<GroupOption>):String=with(layout) {
        when(action.kind) {
            "groups" -> {
                val choices=groupOptions.filter { when(action.option) { "manage","poll_settings"->it.admin;"administrators"->it.superAdmin;else->true } }
                val index=action.page.coerceIn(0,maxOf(0,(choices.size-1)/8))
                choices.drop(index*8).take(8).forEach { row(clean(it.group.title,60),ScreenAction("select_group",0,value=it.group.id,option=action.option)) }
                pages(index,maxOf(1,(choices.size+7)/8),action.copy(group=0))
                row("Назад",ScreenAction(if(action.option in setOf("administrators","poll_settings")) "settings" else "menu",0))
                "Выбери группу."+if(choices.isEmpty()) "\nНет доступных групп." else ""
            }
            "menu" -> {
                requireNotNull(user)
                row("🏓 Мои тренировки",ScreenAction("my_trainings",0))
                row("💰 Мои финансы",ScreenAction("groups",0,option="finance"))
                row("➕ Создать тренировку",ScreenAction("new",0))
                if(groupOptions.any { it.canPublish && polls.enabled(it.group.id) }) row("📊 Создать опрос",ScreenAction("new_poll",0))
                if(groupOptions.any { g -> polls.hasActive(g.group.id,user,g.admin) }) row("📋 Мои опросы",ScreenAction("groups",0,option="polls"))
                row("⚙️ Настройки",ScreenAction("settings",0))
                if(groupOptions.any { it.admin }) row("📋 Управление тренировками",ScreenAction("groups",0,option="manage"))
                "Что хочешь сделать?"
            }
            "settings" -> {
                row("Тренировка",ScreenAction("training_settings",0))
                if(groupOptions.any { it.admin }) row("⚙️ Настройки групп",ScreenAction("groups",0,option="poll_settings"))
                menu()
                "Настройки"
            }
            "poll_settings" -> {
                checkAccounting(service.isAdmin(requireNotNull(a)),ErrorCode.FORBIDDEN,"Настройка доступна администратору этой группы")
                val enabled=polls.enabled(a.groupId)
                val rules=service.groupTrainingRules(a.groupId)
                row(if(rules.guestsEnabled) "👥 Гости: разрешены" else "👥 Гости: запрещены",next("group_rule_save",value=if(rules.guestsEnabled) 0 else 1,option="guests"))
                row(if(rules.trackTime) "🕒 Учёт времени: включён" else "🕒 Учёт времени: выключен",next("group_rule_save",value=if(rules.trackTime) 0 else 1,option="time"))
                row(if(enabled) "📊 Сбор через опрос: включён" else "📊 Сбор через опрос: выключен",next("poll_setting_save",value=if(enabled) 0 else 1))
                if(a.telegramAdmin) row("🛡️ Администраторы группы",next("administrators"))
                row("Назад",ScreenAction("groups",0,option="poll_settings"))
                menu()
                "${clean(service.group(a.groupId).title,60)}\nНастройки группы\nГости и учёт времени применяются к новым и открытым тренировкам. Запрет гостей не удаляет записанных. Без учёта времени стоимость делится поровну; введённые длительности сохраняются и вернутся при включении. Учтённые расчёты не меняются.\nСбор через опрос разрешает создавать опрос перед тренировкой. Обычное создание остаётся доступным; опубликованные опросы можно завершить после выключения."
            }
            "training_settings" -> {
                row("Название",ScreenAction("default_title",0))
                row("Время",ScreenAction("default_time",0))
                rows+=listOf(button("Назад",ScreenAction("settings",0)),button("Меню",ScreenAction("menu",0)))
                "Укажите параметры тренировки по умолчанию"
            }
            else -> error("Not a navigation screen: ${action.kind}")
        }
    }
    companion object { val kinds=setOf("groups","menu","settings","poll_settings","training_settings") }
}
