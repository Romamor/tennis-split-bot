package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class SingleGroupFlowTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val errors=mutableListOf<String>()
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) errors+=text }
    }
    private val clock=object:Clock() {
        var now=Instant.parse("2026-10-06T12:00:00Z")
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=fixed(now,zone)
    }
    private lateinit var bot:SelfServiceBot
    private var seq=1L
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("single.sqlite")),fake.bot,clock)
        bot.service.remember(Account(1,"Игрок"))
    }
    private fun group(id:Long,admin:Boolean=false) {
        bot.service.register(SettlementGroup(id,"Группа ${-id}","Europe/Moscow"))
        bot.service.rememberMembership(id,1,true)
        fake.members[id to 1L]=TgMember(if(admin) "administrator" else "member")
        fake.members[id to fake.bot.id]=TgMember("administrator")
    }
    private fun latest()=fake.messages.values.last { it.chat.id==1L }
    private fun message(text:String) { bot.handle(TgUpdate(seq++,TgMessage(seq,TgChat(1,"private"),TgUser(1,firstName="Игрок"),text))) }
    private fun click(label:String) {
        val m=latest()
        val b=m.keyboard!!.rows.flatten().single { it.text==label || it.text.endsWith(" $label") }
        bot.handle(TgUpdate(seq++,callback=TgCallback("cb$seq",TgUser(1,firstName="Игрок"),m,b.callbackData)))
    }
    private fun create(poll:Boolean=false) {
        message("/start");click(if(poll) "Создать опрос" else "Создать тренировку")
        click("Оставить «Теннис»");click("Продолжить · 06.10.2026");click("Продолжить · 18:30")
        if(poll) click("Оставить «Не приду»")
    }

    @Test fun `one group opens finances directly and back returns to menu`() {
        setup();group(-1);message("/start");click("Мои финансы")
        assertTrue(latest().text!!.contains("Мои финансы"))
        assertEquals(-1L,bot.state.selectedGroup(1,1))
        assertFalse(fake.sent.any { it.text?.startsWith("Выбери группу")==true })
        click("Назад");assertEquals("Что хочешь сделать?",latest().text)
    }

    @Test fun `multiple member groups keep finance picker but only admin group skips settings picker`() {
        setup();group(-1,true);group(-2)
        message("/start");click("Мои финансы")
        assertEquals("Выбери группу.",latest().text)
        click("Группа 2");assertEquals(-2L,bot.state.selectedGroup(1,1))
        click("Назад");click("Настройки");click("Настройки групп")
        assertTrue(latest().text!!.startsWith("Группа 1\nНастройки группы"))
        click("Назад");assertEquals("Настройки",latest().text)
        click("Меню");click("Управление тренировками")
        assertTrue(latest().text!!.startsWith("Тренировки группы"))
        assertEquals(-1L,bot.state.selectedGroup(1,1))
    }

    @Test fun `multiple admin groups return to picker rather than auto opening another group`() {
        setup();group(-1,true);group(-2,true)
        message("/start");click("Настройки");click("Настройки групп");click("Группа 2")
        click("Назад");assertEquals("Выбери группу.",latest().text)
        click("Назад");assertEquals("Настройки",latest().text)
    }

    @Test fun `no groups explains empty choice and never creates a training`() {
        setup();message("/start");click("Мои финансы")
        assertTrue(latest().text!!.contains("Нет доступных групп"))
        click("Назад");create()
        assertEquals("group",bot.state.form(1,1)!!.kind)
        assertNull(bot.state.form(1,1)!!.publishGroup)
        click("Отмена");assertEquals("Что хочешь сделать?",latest().text)
        assertEquals(0,bot.service.myTrainings(1).page.total)
    }

    @Test fun `publication review shows destination and back adapts when eligible group count changes`() {
        setup();group(-1);create()
        assertEquals("ready",bot.state.form(1,1)!!.kind)
        assertTrue(latest().text!!.contains("Группа: Группа 1"))
        assertEquals(0,bot.service.myTrainings(1).page.total)
        group(-2);click("Назад");assertEquals("group",bot.state.form(1,1)!!.kind)
        click("Группа 2");assertEquals(-2L,bot.state.form(1,1)!!.publishGroup)
        fake.members[-2L to 1L]=TgMember("left");clock.now=clock.now.plusSeconds(60)
        click("Назад");assertEquals("time",bot.state.form(1,1)!!.kind)
        click("Продолжить · 18:30");assertEquals(-1L,bot.state.form(1,1)!!.publishGroup)
        click("Опубликовать");assertEquals(1,bot.service.myTrainings(1).page.total)
    }

    @Test fun `poll skips sole eligible group and back returns to decline option without publishing`() {
        setup();group(-1,true);group(-2)
        bot.polls.setEnabled(Access(-1,1,true),true)
        create(poll=true)
        assertEquals("ready",bot.state.form(1,1)!!.kind)
        assertTrue(latest().text!!.contains("Группа: Группа 1"))
        click("Назад");assertEquals("poll_decline",bot.state.form(1,1)!!.kind)
        assertTrue(fake.pollOptions.isEmpty())
        click("Отмена");assertEquals("Что хочешь сделать?",latest().text)
        fake.members[-2L to 1L]=TgMember("left");clock.now=clock.now.plusSeconds(60)
        create(poll=true);click("Опубликовать");bot.maintain()
        message("/start");click("Мои опросы")
        assertTrue(latest().text!!.startsWith("Опросы"))
        click("Назад");assertEquals("Что хочешь сделать?",latest().text)
    }

    @Test fun `automatic selection rechecks membership and administrator rights despite cached menu`() {
        setup();group(-1,true)
        message("/start");fake.members[-1L to 1L]=TgMember("left")
        click("Мои финансы")
        assertTrue(errors.last().contains("Доступ только участникам"))
        assertFalse(latest().text!!.contains("Мои финансы:"))
        fake.members[-1L to 1L]=TgMember("administrator");clock.now=clock.now.plusSeconds(60)
        message("/start");fake.members[-1L to 1L]=TgMember("member")
        click("Управление тренировками")
        assertTrue(errors.last().contains("администратору"))
        assertFalse(latest().text!!.contains("Тренировки группы"))
    }
}
