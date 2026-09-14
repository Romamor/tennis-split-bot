package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import kotlin.test.*

class PanelVisibilityTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val stored=mutableMapOf<Long,TgMessage>()
    private val visible=mutableSetOf<Long>()
    private var nextPanel=100L
    private var seq=1L
    private var sends=0
    private val api=object:TelegramApi by fake {
        override fun ephemeral(chatId:Long,userId:Long,callbackId:String,text:String,keyboard:TgKeyboard):TgMessage {
            sends++
            val id=nextPanel++
            return TgMessage(chat=TgChat(chatId,"supergroup"),from=fake.bot,text=text,keyboard=keyboard,receiver=TgUser(userId),ephemeralId=id)
                .also { stored[id]=it;visible.add(id) }
        }
        override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard)=ephemeral(chatId,userId,callbackId,text,keyboard)
        override fun editEphemeral(chatId:Long,userId:Long,ephemeralId:Long,text:String,keyboard:TgKeyboard) {
            // Telegram accepts editing an old message without showing it again in the client.
            stored[ephemeralId]?.let { stored[ephemeralId]=it.copy(text=text,keyboard=keyboard) }
        }
        override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard)=editEphemeral(chatId,userId,ephemeralId,text,keyboard)
        override fun deleteEphemeral(chatId:Long,userId:Long,ephemeralId:Long) { visible.remove(ephemeralId);stored.remove(ephemeralId) }
    }
    private lateinit var bot:SelfServiceBot
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite")),fake.bot)
        bot.service.remember(Account(1,"Игрок"));bot.service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        bot.service.rememberMembership(-1,1,true)
        fake.members[-1L to 1L]=TgMember("administrator");fake.members[-1L to fake.bot.id]=TgMember("administrator")
    }
    private fun press(m:TgMessage,label:String):TgUpdate {
        val b=m.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        return TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1,firstName="Игрок"),m,b.callbackData)).also(bot::handle)
    }
    private fun panel()=stored.getValue(visible.single())
    @Test fun `open restores an invisible cached panel without duplicate visible menus`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть");visible.clear()
        press(card,"Открыть")
        assertEquals(1,visible.size,"Editing the old cached id must not be mistaken for opening a panel")
        press(card,"Открыть");assertEquals(1,visible.size)
        val id=panel().ephemeralId
        press(panel(),"Присоединиться");assertEquals(id,panel().ephemeralId)
        press(panel(),"Время · 0 ч");assertEquals(id,panel().ephemeralId)
    }
    @Test fun `finish poll opens visible confirmation after a previously dismissed panel`() {
        setup();val a=Access(-1,1,true);bot.polls.setEnabled(a,true)
        val p=bot.polls.create(a,"p","Теннис","2026-09-14","18:30","Не приду")
        PollWorkflow(bot.polls,bot.state,api).publish(p)
        val message=fake.messages.getValue(-1L to bot.polls.get(-1,"p").message!!)
        press(message,"Завершить сбор");visible.clear()
        press(message,"Завершить сбор")
        assertEquals(1,visible.size);assertTrue(panel().text!!.contains("перейти к учёту"))
        press(panel(),"Завершить сбор");bot.finishPollsAfterDrain()
        assertEquals(1,bot.service.myTrainings(1).page.total)
    }
    @Test fun `poll flag toggles preserve opening training and finishing an existing poll`() {
        setup();val a=Access(-1,1,true)
        bot.service.execute(a,"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть");assertEquals(1,visible.size)
        bot.polls.setEnabled(a,true)
        val p=bot.polls.create(a,"p","Сбор","2026-09-14","18:30","Не приду")
        PollWorkflow(bot.polls,bot.state,api).publish(p)
        val poll=fake.messages.getValue(-1L to bot.polls.get(-1,"p").message!!)
        for(enabled in listOf(true,false,true)) {
            bot.polls.setEnabled(a,enabled)
            visible.clear();press(card,"Открыть")
            assertEquals(1,visible.size);assertTrue(panel().keyboard!!.rows.flatten().any { it.text.endsWith("Присоединиться") })
            press(poll,"Завершить сбор")
            assertEquals(1,visible.size);assertTrue(panel().text!!.contains("перейти к учёту"))
        }
        assertTrue(bot.service.training(a,"t").players.isEmpty())
        assertEquals("OPEN",bot.polls.get(-1,"p").status)
    }
    @Test fun `replay after successful panel delivery creates no second panel`() {
        setup();bot.service.execute(Access(-1,1,true),"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-14","18:30"));bot.maintain()
        val card=fake.messages.getValue(-1L to bot.state.delivery("training:-1:t")!!.message!!)
        press(card,"Открыть")
        val id=seq
        bot.service.database.write { c -> sqlUpdate(c,"CREATE TRIGGER fail_completion BEFORE UPDATE OF completed ON bot_events WHEN NEW.update_id=$id AND NEW.completed=1 BEGIN SELECT RAISE(ABORT,'test'); END") }
        val button=card.keyboard!!.rows.single().single()
        val update=TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1),card,button.callbackData))
        assertFailsWith<java.sql.SQLException> { bot.handle(update) }
        val delivered=sends
        bot.service.database.write { c -> sqlUpdate(c,"DROP TRIGGER fail_completion") }
        bot.handle(update)
        assertEquals(delivered,sends);assertEquals(1,visible.size)
    }
}
