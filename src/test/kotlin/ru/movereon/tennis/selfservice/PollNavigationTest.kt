package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import kotlin.test.*

class PollNavigationTest {
    @TempDir lateinit var directory:Path
    private val api=FakeTelegramApi()
    private lateinit var bot:SelfServiceBot
    private var next=1L
    private fun setup() {
        bot=SelfServiceBot(api,Database(directory.resolve("poll-navigation.sqlite")),api.bot)
        for(g in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..3L) {
                bot.service.remember(Account(u,"Игрок $u"));bot.service.rememberMembership(g,u,true)
                api.members[g to u]=TgMember(if(u==1L && g==-1L) "administrator" else "member")
            }
            api.members[g to api.bot.id]=TgMember("administrator",user=api.bot)
            bot.polls.setEnabled(Access(g,1,true),true)
        }
    }
    private fun latest(u:Long)=api.messages.values.last { it.chat.id==u }
    private fun start(u:Long)=bot.handle(TgUpdate(next++,TgMessage(next,TgChat(u,"private"),TgUser(u,firstName="Игрок $u"),"/start")))
    private fun labels(u:Long)=latest(u).keyboard!!.rows.flatten().map { it.text }
    private fun click(u:Long,text:String) {
        val m=latest(u);val b=m.keyboard!!.rows.flatten().single { it.text==text || it.text.endsWith(" $text") }
        bot.handle(TgUpdate(next++,callback=TgCallback("cb$next",TgUser(u,firstName="Игрок $u"),m,b.callbackData)))
    }
    @Test fun `menu hides empty polls and a single eligible group bypasses both picker and back`() {
        setup();start(2);assertFalse(labels(2).contains("📋 Мои опросы"))
        bot.polls.create(Access(-1,3),"other","Другой сбор","2026-10-08","18:30","Не приду")
        start(2);assertFalse(labels(2).contains("📋 Мои опросы"))
        val own=bot.polls.create(Access(-2,2),"own","Мой сбор","2026-10-08","18:30","Не приду")
        start(2);click(2,"Мои опросы")
        assertEquals("Опросы · 1",latest(2).text);assertTrue(labels(2).any { it.contains("Мой сбор") })
        assertFalse(labels(2).any { it.contains("Другой сбор") });click(2,"Назад")
        assertEquals("Что хочешь сделать?",latest(2).text)
        // The Telegram admin sees another author's polls, only in the group where those rights exist.
        start(1);click(1,"Мои опросы");assertTrue(labels(1).any { it.contains("Другой сбор") })
        assertFalse(labels(1).any { it.contains("Мой сбор") })
        bot.polls.status(own,"CLOSED");start(2);assertFalse(labels(2).contains("📋 Мои опросы"))
    }
    @Test fun `picker includes only groups with manageable polls and removes closed groups`() {
        setup()
        val first=bot.polls.create(Access(-1,2),"first","Первый","2026-10-08","18:30","Не приду")
        bot.polls.create(Access(-2,2),"second","Второй","2026-10-08","18:30","Не приду")
        start(2);click(2,"Мои опросы")
        assertEquals("Выбери группу.",latest(2).text);assertTrue(labels(2).containsAll(listOf("👥 Группа 1","👥 Группа 2")))
        click(2,"Группа 2");assertTrue(labels(2).any { it.contains("Второй") });click(2,"Назад")
        assertEquals("Выбери группу.",latest(2).text)
        bot.polls.status(first,"CLOSED");start(2);click(2,"Мои опросы")
        assertEquals("Опросы · 1",latest(2).text);assertTrue(labels(2).any { it.contains("Второй") })
    }
}
