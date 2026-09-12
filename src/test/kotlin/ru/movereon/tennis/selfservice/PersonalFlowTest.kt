package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class PersonalFlowTest {
    @TempDir lateinit var dir:Path
    private val api=FakeTelegramApi()
    private val clock=object:Clock() {
        var now=Instant.parse("2026-09-13T12:00:00Z")
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=fixed(now,zone)
    }
    private lateinit var bot:SelfServiceBot
    private var sequence=1L
    private fun setup(groups:Boolean=true) {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite")),api.bot,clock)
        for(u in 1L..2L) bot.service.remember(Account(u,"Игрок $u"))
        if(groups) for(g in -4L..-1L) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..2L) {
                bot.service.rememberMembership(g,u,true)
                api.members[g to u]=TgMember(if(u==1L && g==-3L) "left" else if(u==2L) "administrator" else "member")
            }
            api.members[g to api.bot.id]=TgMember(when(g) { -2L->"member";-4L->"left";else->"administrator" },user=api.bot)
        }
    }
    private fun latest(user:Long=1)=api.messages.values.last { it.chat.id==user }
    private fun rows(user:Long=1)=latest(user).keyboard!!.rows.map { row->row.map { it.text } }
    private fun message(text:String,user:Long=1) {
        bot.handle(TgUpdate(sequence++,TgMessage(sequence,TgChat(user,"private"),TgUser(user,firstName="Игрок $user"),text)))
    }
    private fun click(label:String,user:Long=1) {
        val m=latest(user);val b=m.keyboard!!.rows.flatten().single { it.text==label }
        bot.handle(TgUpdate(sequence++,callback=TgCallback("cb$sequence",TgUser(user,firstName="Игрок $user"),m,b.callbackData)))
    }
    private fun toGroupStep() {
        message("/start");click("➕ Создать тренировку");click("Оставить «Теннис»")
        click("Продолжить · 13.09.2026");click("Продолжить · 18:30")
    }

    @Test fun `creation is groupless preserves fields on back and cancels directly to the main menu`() {
        setup();message("/start");click("➕ Создать тренировку")
        assertEquals("Напиши название тренировки.",latest().text)
        assertEquals(listOf("Отмена"),rows().last())
        assertEquals(0,bot.state.form(1,1)!!.group)
        message("Спарринг");assertEquals(listOf("Назад","Отмена"),rows().last())
        message("21.09.2026");click("▲ Часы");click("Назад")
        assertEquals("2026-09-21",bot.state.form(1,1)!!.date)
        assertEquals("19:30",bot.state.form(1,1)!!.time)
        click("Назад");assertEquals("Спарринг",bot.state.form(1,1)!!.title)
        click("Отмена");assertEquals("Что хочешь сделать?",latest().text)
        assertNull(bot.state.form(1,1));assertEquals(0,bot.service.myTrainings(1).page.total)
    }

    @Test fun `group eligibility is checked at selection and again at publication`() {
        setup();toGroupStep()
        assertEquals(listOf(listOf("Группа 1"),listOf("Назад","Отмена")),rows())
        api.members[-1L to api.bot.id]=TgMember("member",user=api.bot)
        click("Группа 1");assertEquals("group",bot.state.form(1,1)!!.kind)
        api.members[-1L to api.bot.id]=TgMember("administrator",user=api.bot)
        click("Группа 1")
        assertEquals(listOf(listOf("Опубликовать"),listOf("Назад","Отмена")),rows())
        click("Назад");assertEquals("group",bot.state.form(1,1)!!.kind)
        click("Группа 1");api.members[-1L to 1L]=TgMember("left")
        click("Опубликовать");assertEquals(0,bot.service.myTrainings(1).page.total)
        api.members[-1L to 1L]=TgMember("member");click("Опубликовать")
        assertEquals(1,bot.service.myTrainings(1).page.total)
        assertEquals(listOf("Открыть","Редактировать","Назад"),rows().flatten())
    }

    @Test fun `personal settings and main menu work before joining any group`() {
        setup(groups=false);message("/start");click("⚙️ Настройки");click("Тренировка")
        assertEquals("Укажите параметры тренировки по умолчанию",latest().text)
        assertEquals(listOf(listOf("Название"),listOf("Время"),listOf("Назад","Меню")),rows())
        click("Название");message("Спарринг");click("Сохранить название")
        click("Время");click("▲ Часы");click("✅ Сохранить время")
        assertEquals(TrainingDefaults("Спарринг","19:30"),bot.service.trainingDefaults(1))
        assertEquals(TrainingDefaults(),bot.service.trainingDefaults(2))
        click("Меню");click("➕ Создать тренировку");click("Оставить «Спарринг»")
        click("Продолжить · 13.09.2026");assertEquals("19:30",bot.state.form(1,1)!!.time)
        click("Продолжить · 19:30");assertEquals(listOf(listOf("Назад","Отмена")),rows())
        click("Отмена");assertEquals("Что хочешь сделать?",latest().text)
    }

    @Test fun `group selection paginates and back from preview restores the selected page`() {
        setup()
        for(g in -18L..-10L) {
            bot.service.register(SettlementGroup(g,"Новая ${-g}","Europe/Moscow"))
            bot.service.rememberMembership(g,1,true)
            api.members[g to 1L]=TgMember("member")
            api.members[g to api.bot.id]=TgMember("administrator",user=api.bot)
        }
        toGroupStep();assertEquals(8,rows().flatten().count { it.startsWith("Группа")||it.startsWith("Новая") })
        click("Дальше ›");val selected=rows().first().single()
        assertEquals(1,bot.state.form(1,1)!!.page)
        click(selected);click("Назад")
        assertEquals(1,bot.state.form(1,1)!!.page);assertEquals(selected,rows().first().single())
        click("Назад");assertEquals("time",bot.state.form(1,1)!!.kind)
        assertEquals("18:30",bot.state.form(1,1)!!.time)
    }

    @Test fun `my trainings aggregate groups paginate by creation date and open a separate viewer`() {
        setup()
        for(i in 1..5) {
            clock.now=clock.now.plusSeconds(3600)
            val g=if(i%2==0) -2L else -1L;val a=Access(g,2,true);val id="t$i"
            val creator=if(i==4) Access(g,1) else a
            bot.service.execute(creator,"create$i",SettlementCommand.CreateTraining(id,"Тренировка $i","2026-09-${30-i}","18:30"))
            bot.service.execute(a,"players$i",SettlementCommand.AddPlayers(id,1,listOf(1)))
            bot.service.execute(a,"time$i",SettlementCommand.ChangeAttendance(id,1,AttendanceChange.SET_MINUTES,i*30L))
            bot.service.execute(a,"paid$i",SettlementCommand.ChangeAttendance(id,1,AttendanceChange.SET_PAID,i*50L))
            if(i%2==0) bot.service.execute(a,"close$i",SettlementCommand.FinishTraining(id,bot.service.training(a,id).version))
            if(i==3) bot.service.execute(a,"cancel$i",SettlementCommand.CancelTraining(id,bot.service.training(a,id).version))
        }
        message("/start");click("🏓 Мои тренировки")
        assertEquals("Тренировок: 5\nВремя: 7,5 ч\nПотрачено денег: 750 ₽",latest().text)
        val first=rows().take(3).flatten();assertTrue(first[0].contains("Тренировка 5"));assertTrue(first[2].contains("Тренировка 3"))
        click(first[2]);assertEquals(listOf(listOf("Назад")),rows())
        assertFalse(latest().text!!.contains("не влияет на баланс"));click("Назад")
        assertEquals(first,rows().take(3).flatten())
        click(first[1]);assertEquals(listOf(listOf("Редактировать"),listOf("Назад")),rows())
        click("Редактировать");click("Изменить статус");assertEquals(listOf("Открыта","⬅️ Назад"),rows().flatten());click("⬅️ Назад")
        click("Назад");click("Назад");click("Дальше ›")
        val second=rows().take(2).flatten();assertTrue(second[0].contains("Тренировка 2"));assertTrue(second[1].contains("Тренировка 1"))
        click(second[1]);assertEquals(listOf(listOf("Открыть"),listOf("Назад")),rows())
        click("Назад");assertEquals(second,rows().take(2).flatten())
    }
}
