package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.telegram.*
import ru.movereon.tennis.selfservice.Screens.Companion.phase
import ru.movereon.tennis.selfservice.Screens.Companion.hours

internal data class ScreenContent(val text:String,val html:String?=null)

/** Cards, editor and shared live attendance controls. No Telegram calls or financial writes. */
internal class TrainingScreens(private val service:SettlementService,private val state:InteractionStore) {
    fun render(layout:ScreenLayout,a:Access?,personRow:(Long,String,ScreenAction)->Unit,label:(Long)->String,account:(Long)->Account):ScreenContent = with(layout) {
        var richHtml:String?=null
        val text=when(action.kind) {
            "training", "public", "my_training" -> {
                val t = service.training(requireNotNull(a), action.id)
                val content=TrainingCard.render(t,account)
                richHtml=content.html
                val index=0
                var body=content.text
                if(action.kind=="training" && service.isAdmin(a)) {
                    val pin=state.pinStatus("training:${t.groupId}:${t.id}")
                    val warning=when(pin) {
                        "UNPIN_FAILED" -> "Не удалось снять карточку с закрепа. Проверь право бота закреплять сообщения."
                        "FAILED" -> "Карточка не закреплена. Проверь право бота закреплять сообщения."
                        "UNKNOWN" -> "Результат закрепления неизвестен. Проверь закреплённые сообщения перед повтором."
                        else -> null
                    }
                    if(warning!=null) { body+="\n\n$warning";richHtml=content.html+"<p>${TrainingCard.escape(warning)}</p>" }
                }
                if (action.kind == "public") {
                    row("Открыть",next("participation",page=index))
                } else if(action.kind=="my_training") {
                    if(t.phase==TrainingPhase.OPEN) row("Открыть",next("participation").copy(back=action))
                    if(service.canEdit(a,t)) row("Редактировать",next("edit_training").copy(back=action))
                    row("Назад",action.back ?: ScreenAction("my_trainings",0))
                } else {
                    checkAccounting(service.canEdit(a,t),ErrorCode.FORBIDDEN,"Редактировать тренировку может её создатель или администратор этой группы")
                    row("Изменить статус",next("training_status").copy(back=action))
                    row("Изменить название и время",next("edit_details",version=t.version).copy(back=action))
                    rows+=listOf(button("Добавить игрока",next("add_player_list").copy(back=action)),button("Исключить игрока",next("exclude_player_list").copy(back=action)))
                    row("Управление игроками",next("manage_players").copy(back=action))
                    row("История изменений",next("history").copy(back=action))
                    if(service.isAdmin(a) && state.pinStatus("training:${t.groupId}:${t.id}") in setOf("FAILED","UNKNOWN","UNPIN_FAILED"))
                        row(if(t.phase==TrainingPhase.OPEN) "📌 Повторить закрепление" else "📌 Повторить снятие закрепа",next("retry_pin").copy(back=action))
                    if(service.isAdmin(a) && state.delivery("training:${t.groupId}:${t.id}")?.status in setOf("UNKNOWN","FAILED"))
                        row("Восстановить сообщение в группе",next("recover_confirm").copy(back=action))
                    rows+=buildList<TgButton> {
                        action.back?.let { add(button("Назад",it)) }
                        add(button("Меню",ScreenAction("menu",0)))
                    }
                }
                body
            }
            "training_status" -> {
                val t=service.training(requireNotNull(a),action.id)
                checkAccounting(service.canEdit(a,t),ErrorCode.FORBIDDEN,"Изменять статус может создатель или администратор")
                val targets=TrainingLifecycle.targets(t.phase)
                targets.forEach { target -> row(if(target==TrainingPhase.CLOSED) "Завершена" else phase(target),next("set_training_status",version=t.version,option=target.name)) }
                back(next("training"))
                "Текущий статус: ${phase(t.phase)}.\nВыбери новый статус."
            }
            "add_player_list", "exclude_player_list", "manage_players" -> {
                val t=service.training(requireNotNull(a),action.id)
                checkAccounting(service.canEdit(a,t),ErrorCode.FORBIDDEN,"Изменять игроков может создатель или администратор")
                service.requireOpen(t)
                val players=t.players.filter { it.playing || it.paid>0 }.map { it.userId }
                val ids=if(action.kind=="add_player_list") service.rosterIds(a,presentOnly=true).filter { id -> t.players.none { it.userId==id && it.playing } } else players
                val index=action.page.coerceIn(0,maxOf(0,(ids.size-1)/8))
                ids.drop(index*8).take(8).forEach { id ->
                    val kind=when(action.kind) { "add_player_list"->"add_player";"exclude_player_list"->"remove_player";else->"participation" }
                    personRow(id,label(id),next(kind,target=id).copy(back=action.copy(page=index)))
                }
                pages(index,maxOf(1,(ids.size+7)/8))
                if(action.kind=="add_player_list") row("Добавить через Telegram",next("pick_add_player").copy(back=action.copy(page=index)))
                back(next("training"))
                when(action.kind) { "add_player_list"->"Кого добавить?";"exclude_player_list"->"Кого исключить?";else->"Выбери игрока." }+if(ids.isEmpty()) "\nСписок пуст." else ""
            }
            "participation", "participation_time", "participation_payment" -> {
                val t=service.training(requireNotNull(a),action.id)
                service.requireOpen(t)
                val content=TrainingCard.render(t,account)
                richHtml=content.html
                val target=action.user.takeIf { it>0 } ?: a.userId
                checkAccounting(target==a.userId || service.canEdit(a,t),ErrorCode.FORBIDDEN,"Можно менять только свои данные")
                val p=t.players.firstOrNull { it.userId==target }
                val open=t.phase == TrainingPhase.OPEN
                fun change(label:String,type:AttendanceChange,value:Long=0)=button(label,next("participation_change",page=0,target=target,value=value,option=type.name).copy(resume=action.copy(page=0)))
                if(open) when(action.kind) {
                    "participation_time" -> {
                        checkAccounting(p?.playing==true,ErrorCode.INVALID_STATE,"Сначала присоединись к тренировке")
                        rows+=listOf(change("+0,5 ч",AttendanceChange.ADJUST_MINUTES,30),change("+1 ч",AttendanceChange.ADJUST_MINUTES,60))
                        rows+=listOf(change("−0,5 ч",AttendanceChange.ADJUST_MINUTES,-30),change("−1 ч",AttendanceChange.ADJUST_MINUTES,-60))
                    }
                    "participation_payment" -> {
                        checkAccounting(p?.playing==true || (p?.paid ?: 0)>0,ErrorCode.INVALID_STATE,"Сначала присоединись к тренировке")
                        rows+=listOf(5L,50L,100L).map { change("+$it ₽",AttendanceChange.ADJUST_PAID,it) }
                        rows+=listOf(5L,50L,100L).map { change("−$it ₽",AttendanceChange.ADJUST_PAID,-it) }
                    }
                    else -> {
                        if(p?.playing==true) {
                            row("Оплата · ${p.paid} ₽",next("participation_payment",page=0,target=target))
                            row("Время · ${hours(p.minutes)}",next("participation_time",page=0,target=target))
                            rows+=buildList<TgButton> {
                                if(p.guestCount<99) add(change("Добавить гостя",AttendanceChange.ADJUST_GUESTS,1))
                                if(p.guestCount>0) add(change("Убрать гостя",AttendanceChange.ADJUST_GUESTS,-1))
                            }
                            rows+=listOf(change("Не участвую",AttendanceChange.LEAVE))
                        } else {
                            rows+=listOf(change("Присоединиться",AttendanceChange.JOIN))
                            if((p?.paid ?: 0)>0) row("Оплата · ${p!!.paid} ₽",next("participation_payment",page=0,target=target))
                        }
                    }
                }
                if(action.kind=="participation" && target==a.userId && action.back?.kind!="manage_players" && service.canEdit(a,t))
                    row("Редактировать",ScreenAction("edit_training",t.groupId,t.id,back=action.back))
                if(inGroup) rows+=buildList<TgButton> {
                    if(action.kind!="participation") add(button("⬅️ Назад",next("participation",page=0)))
                    add(button("Закрыть",next("close_panel")))
                } else if(action.kind!="participation") row("⬅️ Назад",next("participation",page=0))
                else back(next("training"))
                content.text
            }
            else -> error("Not a training screen: ${action.kind}")
        }
        ScreenContent(text,richHtml)
    }
    companion object {
        val kinds=setOf("training","public","my_training","training_status","add_player_list","exclude_player_list","manage_players","participation","participation_time","participation_payment")
    }
}
