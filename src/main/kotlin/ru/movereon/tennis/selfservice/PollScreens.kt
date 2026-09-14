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
                row("Назад",ScreenAction("groups",0,option="polls"))
                "Опросы · ${page.total}"
            }
            "poll_detail", "poll_close_confirm", "poll_discard_confirm" -> {
                val p=polls.get(action.group,action.id)
                polls.requireManager(requireNotNull(a),p)
                if(action.kind=="poll_discard_confirm") {
                    row("Отменить опрос",next("poll_discard"))
                    row("Назад",next("poll_detail"))
                    "Отменить неудавшуюся публикацию? Тренировка создана не будет. Затем можно создать новый опрос. Если сообщение появилось в группе, но бот не получил его адрес, удали его вручную."
                } else if(action.kind=="poll_close_confirm" && p.status=="OPEN") {
                    row("🏁 Завершить сбор",next("poll_close"))
                    row("Назад",next(if(inGroup) "close_panel" else "poll_detail"))
                    "Завершить сбор и перейти к учёту игры?\n${clean(p.title,100)} · ${date(p.date)} · ${p.time}\nЗаписались: ${polls.count(p)}.\n${if(service.groupTrainingRules(p.group).trackTime) "Они будут добавлены с 0 ч игры и 0 ₽. Время и оплату можно исправить в тренировке." else "Они будут добавлены с 0 ₽. Стоимость делится поровну; ввод времени не нужен."}"
                } else {
                    if(p.status=="OPEN") row("🏁 Завершить сбор",next("poll_close_confirm"))
                    if(p.status in setOf("FAILED","PENDING")) row("📤 Повторить публикацию",next("poll_retry"))
                    if(p.status in setOf("UNKNOWN","FAILED","PENDING")) row("Отменить опрос",next("poll_discard_confirm"))
                    if(p.status=="CLOSED") row("🏓 Открыть тренировку",ScreenAction("training",p.group,requireNotNull(p.training)))
                    if(inGroup) row("Закрыть",next("close_panel")) else { row("Назад",next("poll_list"));menu() }
                    "${clean(p.title,100)} · ${date(p.date)} · ${p.time}\n"+when(p.status) {
                        "OPEN" -> "Сбор открыт. Записались: ${polls.count(p)}."
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
    companion object { val kinds=setOf("poll_list","poll_detail","poll_close_confirm","poll_discard_confirm") }
}
