package ru.movereon.tennis.selfservice

import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.selfservice.Screens.Companion.clean
import ru.movereon.tennis.selfservice.Screens.Companion.date

/** Poll lists and confirmation screens. No Telegram delivery or attendance mutation. */
internal class PollScreens(private val service:SettlementService,private val polls:TrainingPolls) {
    fun render(layout:ScreenLayout,a:Access?):String=with(layout) {
        when(action.kind) {
            "poll_list" -> {
                val auth=requireNotNull(a)
                val page=polls.managedPage(auth,action.page)
                val index=page.index
                page.items.forEach { row("${date(it.date)} · ${clean(it.title,32)}",next("poll_detail",it.id).copy(back=action.copy(page=index))) }
                pages(index,page.pages)
                row("Назад",ScreenAction("groups_back",0,option="polls"))
                "Опросы · ${page.total}"
            }
            "poll_detail", "poll_close_confirm", "poll_discard_confirm", "poll_close_recover_confirm" -> {
                val p=polls.get(action.group,action.id)
                polls.requireManager(requireNotNull(a),p)
                val signups=if(p.status=="OPEN" || p.awaitingCloseRecovery) polls.count(p) else null
                val overCapacity=(p.status=="OPEN" || p.awaitingCloseRecovery && p.stopped) && requireNotNull(signups)>MAX_TRAINING_PARTICIPANTS
                if(overCapacity) {
                    if(!inGroup) { row("Назад",next("poll_list"));menu() }
                    "${clean(p.title,100)} · ${date(p.date)} · ${p.time}\n"+
                        "${if(p.stopped) "Голосование остановлено." else "Сбор остаётся открытым."} Записались: $signups. В одной тренировке может быть не больше $MAX_TRAINING_PARTICIPANTS участников, включая гостей.\n"+
                        if(p.stopped) "Тренировка не создана; все ответы сохранены. Создай отдельные тренировки и распредели участников вручную."
                        else "Участники могут отозвать свой голос. Когда останется не больше $MAX_TRAINING_PARTICIPANTS записавшихся, завершение станет доступно. Все ответы сохранены."
                } else if(action.kind=="poll_discard_confirm") {
                    checkAccounting(!p.awaitingCloseRecovery,ErrorCode.INVALID_STATE,"Опубликованный опрос нужно завершить")
                    row("Отменить опрос",next("poll_discard"))
                    row("Назад",next("poll_detail"))
                    "Отменить неудавшуюся публикацию? Тренировка создана не будет. Затем можно создать новый опрос. Если сообщение появилось в группе, но бот не получил его адрес, удали его вручную."
                } else if(action.kind=="poll_close_recover_confirm" && p.awaitingCloseRecovery && p.stopped) {
                    row("🏁 Создать тренировку",next("poll_close_recover"))
                    row("Назад",next("poll_detail"))
                    "Голосование остановлено или сообщение опроса удалено. Создать тренировку по сохранённым голосам?\n${clean(p.title,100)} · ${date(p.date)} · ${p.time}\nСейчас записались: $signups. Последние полученные голоса будут обработаны перед созданием тренировки."
                } else if(action.kind=="poll_close_confirm" && p.status=="OPEN") {
                    row("🏁 Завершить сбор",next("poll_close"))
                    if(!inGroup) row("Назад",next("poll_detail"))
                    "Завершить сбор и перейти к учёту игры?\n${clean(p.title,100)} · ${date(p.date)} · ${p.time}\nЗаписались: $signups.\n${if(service.groupTrainingRules(p.group).trackTime) "Они будут добавлены с 0 ч игры и 0 ₽. Время и оплату можно исправить в тренировке." else "Они будут добавлены с 0 ₽. Стоимость делится поровну; ввод времени не нужен."}"
                } else {
                    if(p.status=="OPEN") row("🏁 Завершить сбор",next("poll_close_confirm"))
                    if(p.awaitingCloseRecovery) {
                        if(p.stopped) row("🏁 Создать тренировку",next("poll_close_recover_confirm"))
                        else row("Повторить завершение",next("poll_close_retry"))
                    }
                    if(p.status in setOf("FAILED","PENDING") && !p.awaitingCloseRecovery) row("📤 Повторить публикацию",next("poll_retry"))
                    if(p.status in setOf("UNKNOWN","FAILED","PENDING") && !p.awaitingCloseRecovery) row("Отменить опрос",next("poll_discard_confirm"))
                    if(p.status=="CLOSED") row("🏓 Открыть тренировку",ScreenAction("training",p.group,requireNotNull(p.training)))
                    if(!inGroup) { row("Назад",next("poll_list"));menu() }
                    "${clean(p.title,100)} · ${date(p.date)} · ${p.time}\n"+if(p.awaitingCloseRecovery) {
                        if(p.stopped) "Не удалось обновить сообщение опроса. Голосование остановлено или сообщение удалено. Сохранённые голоса не потеряны; можно создать тренировку после подтверждения."
                        else "Не удалось остановить опрос. Сохранённые голоса не потеряны. Восстанови доступ бота к группе и повтори завершение. До подтверждённой остановки тренировка не создаётся."
                    } else when(p.status) {
                        "OPEN" -> "Сбор открыт. Записались: $signups."
                        "CLOSING" -> "Завершаем сбор. Тренировка появится в группе после обработки последних голосов. Если она не появляется, проверь права бота в группе."
                        "CLOSED" -> "Сбор завершён. Тренировка создана."
                        "UNKNOWN","SENDING" -> "Telegram не подтвердил публикацию. Часть голосов могла не дойти до бота, поэтому создавать тренировку из этого опроса нельзя. Отмени его и создай новый. Автоматического повтора нет, чтобы не создать второй опрос."
                        else -> "Опрос не опубликован. Проверь права бота и повтори публикацию."
                    }
                }
            }
            else -> error("Not a poll screen: ${action.kind}")
        }
    }
    companion object { val kinds=ActionRegistry.kinds(ActionRoute.POLL_SCREEN) }
}
