package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import kotlin.test.*

class TrainingCapacityButtonTest {
    @TempDir lateinit var directory:Path

    @Test fun `fifty first join button shows the participant limit and leaves attendance unchanged`() {
        val fake=FakeTelegramApi()
        val alerts=mutableListOf<String>()
        val api=object:TelegramApi by fake {
            override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
        }
        val bot=SelfServiceBot(api,Database(directory.resolve("capacity-button.sqlite")),fake.bot)
        val admin=Access(-1,1,true)
        bot.service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        fake.members[-1L to fake.bot.id]=TgMember("administrator")
        (1L..51L).forEach { user ->
            bot.service.remember(Account(user,"Игрок $user"))
            bot.service.rememberMembership(-1,user,true)
            fake.members[-1L to user]=TgMember(if(user==1L) "administrator" else "member")
        }
        bot.service.execute(admin,"create",SettlementCommand.CreateTraining("t","Теннис","2026-11-06","18:30"))
        bot.service.execute(admin,"full-roster",SettlementCommand.AddPlayers("t",1,(1L..50L).toList()))
        val before=bot.service.training(admin,"t")
        val history=bot.service.history(admin).total
        val balances=bot.service.balances(admin)
        val user=TgUser(51,firstName="Игрок 51")
        val token=bot.state.button(ScreenAction("participation",-1,"t"),51,"capacity-entry",permanent=true)
        bot.handle(TgUpdate(1,message=TgMessage(1,TgChat(51,"private"),user,"/start n_$token")))
        val card=fake.messages.values.last { it.chat.id==51L }
        val join=requireNotNull(card.keyboard).rows.flatten().single { it.text.endsWith("Присоединиться") }
        val click=TgUpdate(2,callback=TgCallback("join",user,card,requireNotNull(join.callbackData)))
        bot.handle(click)
        assertEquals(1,alerts.size)
        assertContains(alerts.single(),"50 участников, включая гостей")
        assertFalse(alerts.single().contains("сумму или время"))
        assertEquals(before,bot.service.training(admin,"t"))
        assertEquals(history,bot.service.history(admin).total)
        assertEquals(balances,bot.service.balances(admin))
        assertTrue(bot.state.completed(click.id))
    }
}
