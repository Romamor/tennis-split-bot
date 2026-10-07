package ru.movereon.tennis.selfservice

import ru.movereon.tennis.telegram.*

/** Shared keyboard/token builder for one message, under InteractionStore.buttonBatch. */
internal class ScreenLayout(
    val action:ScreenAction, private val state:InteractionStore, private val botName:String,
    private val scope:String, val user:Long?, val inGroup:Boolean,
) {
    val rows = mutableListOf<List<TgButton>>()
    val tokens = mutableSetOf<String>()
    private val actions=mutableMapOf<TgButton,ScreenAction>()
    fun keyboard()=ButtonAppearance.keyboard(rows,actions)
    fun button(label: String, next: ScreenAction): TgButton {
        if (inGroup && next.kind in Screens.privateActions) {
            val token = state.button(next, null, "private-link:${next.group}:${next.id}", permanent = true)
            return TgButton(label, url = "https://t.me/$botName?start=n_$token").also { actions[it]=next }
        }
        val token = state.button(next, user, scope)
        tokens += token
        return TgButton(label, callbackData = "n:$token").also { actions[it]=next }
    }
    fun control(label:String,next:ScreenAction,enabled:Boolean=true,style:String?=null):TgButton =
        if(enabled) button(label,next).copy(style=style) else TgButton(label,disabled=kotlinx.serialization.json.JsonObject(emptyMap()))
    fun inline(label:String,next:ScreenAction,style:String="link"):String {
        val b=button(label,next)
        val kind=if(b.callbackData!=null) "callback_data" else "url"
        val attr=if(b.callbackData!=null) "data" else "url"
        return "<tg-button type=\"$kind\" style=\"$style\" $attr=\"${TrainingCard.escape(b.callbackData ?: requireNotNull(b.url))}\">${TrainingCard.escape(label)}</tg-button>"
    }
    fun next(kind: String, id: String = action.id, page: Int = 0, target: Long = action.user, value: Long = 0, version: Long = action.version, option: String = action.option) =
        ScreenAction(kind, action.group, id, page, target, value, version, option,back=action.back,resume=action.resume)
    fun row(label: String, next: ScreenAction) { rows += listOf(button(label, next)) }
    fun pages(index: Int, total: Int, base: ScreenAction = action) {
        if (total > 1) rows += buildList<TgButton> {
            if (index > 0) add(button("‹ Назад", base.copy(page = index - 1)))
            add(button("${index + 1} / $total", base.copy(page = index)))
            if (index + 1 < total) add(button("Дальше ›", base.copy(page = index + 1)))
        }
    }
    fun menu() { row("Меню", ScreenAction("menu",0)) }
    fun back(fallback:ScreenAction) { row("⬅️ Назад",action.back ?: fallback) }
}
