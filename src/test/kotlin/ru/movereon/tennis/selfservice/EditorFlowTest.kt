package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.AccountingException
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import kotlin.test.*

class EditorFlowTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private val alerts=mutableListOf<String>()
    private val api=object:TelegramApi by fake {
        override fun answer(callbackId:String,text:String?,alert:Boolean) { if(alert && text!=null) alerts+=text }
        override fun requestUsers(chatId:Long,text:String,requestId:Int)=fake.send(chatId,text,null,false)
    }
    private lateinit var bot:SelfServiceBot
    private var sequence=1L
    private val creator=Access(-1,1)
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("editor.sqlite")),fake.bot)
        for(g in -2L..-1L) {
            bot.service.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..14L) {
                bot.service.remember(Account(u,"Игрок $u"))
                bot.service.rememberMembership(g,u,u!=14L)
                fake.members[g to u]=TgMember(if(u==14L) "left" else if(u==2L) "administrator" else "member")
            }
            fake.members[g to fake.bot.id]=TgMember("administrator",user=fake.bot)
        }
        bot.service.execute(creator,"create",SettlementCommand.CreateTraining("t","Теннис","2026-09-13","18:30"))
        openEditor()
    }
    private fun latest(user:Long=1)=fake.messages.values.last { it.chat.id==user && it.keyboard!=null }
    private fun rows(user:Long=1)=latest(user).keyboard!!.rows.map { row->row.map { it.text } }
    private fun message(text:String,user:Long=1) {
        bot.handle(TgUpdate(sequence++,TgMessage(sequence,TgChat(user,"private"),TgUser(user,firstName="Игрок $user"),text)))
    }
    private fun click(text:String,user:Long=1,source:TgMessage=latest(user)):TgUpdate {
        val button=source.keyboard!!.rows.flatten().single { it.text==text || it.text.endsWith(" $text") }
        return TgUpdate(sequence++,callback=TgCallback("cb$sequence",TgUser(user,firstName="Игрок $user"),source,button.callbackData)).also(bot::handle)
    }
    private fun openEditor(user:Long=1) {
        val token=bot.state.button(ScreenAction("edit_training",-1,"t"),null,"edit-link:-1:t",permanent=true)
        message("/start n_$token",user)
    }
    private fun training()=bot.service.training(creator,"t")
    private fun status(target:String) { click("Изменить статус");click(target) }

    @Test fun `editor layout and back route depend on entry point`() {
        setup()
        assertEquals(listOf(listOf("🔄 Изменить статус"),listOf("✏️ Изменить название и время"),listOf("Добавить игрока","Исключить игрока"),listOf("👥 Управление игроками"),listOf("📜 История изменений"),listOf("🏠 Меню")),rows())
        assertEquals(TrainingCard.render(training(),bot.service::account).text,latest().text)
        message("/start");click("🏓 Мои тренировки")
        click(rows().first().single());click("Редактировать")
        assertEquals(listOf("Назад","Меню"),rows().last())
        click("Назад");assertEquals(listOf("🏓 Открыть","✏️ Редактировать","⬅️ Назад"),rows().flatten())
        openEditor(3);assertTrue(latest(3).text!!.contains("создатель"))
        openEditor(2);assertTrue(rows(2).flatten().any { it.endsWith("Изменить статус") })
    }

    @Test fun `adding excluding and managing players save immediately with the real editor in history`() {
        setup();click("Добавить игрока")
        assertEquals(8,rows().flatten().count { it.contains("Игрок ") })
        assertFalse(rows().flatten().any { it.endsWith("Игрок 14") })
        click("Игрок 1");assertFalse(rows().flatten().any { it.endsWith("Игрок 1") })
        click("Игрок 2");click("⬅️ Назад");click("Управление игроками");click("Игрок 2")
        assertFalse(rows().flatten().contains("Закрыть"));assertFalse(rows().flatten().contains("Присоединиться"))
        click("Время · 0 ч");click("+1 ч")
        assertEquals(60,training().players.single { it.userId==2L }.minutes)
        assertEquals(0,training().players.single { it.userId==1L }.minutes)
        assertEquals(1,bot.service.history(creator,trainingId="t").items.first().actorId)
        assertFalse(rows().flatten().contains("Закрыть"))
        click("⬅️ Назад");click("Оплата · 0 ₽");click("+100 ₽")
        assertEquals(100,training().players.single { it.userId==2L }.paid)
        click("⬅️ Назад");click("Добавить гостя")
        assertEquals(1,training().players.single { it.userId==2L }.guestCount)
        click("⬅️ Назад");click("⬅️ Назад");click("Исключить игрока")
        val before=training();click("Игрок 2")
        assertTrue(alerts.last().contains("оплата"));assertEquals(before,training())
        click("Игрок 1");assertFalse(rows().flatten().any { it.endsWith("Игрок 1") });assertTrue(training().players.none { it.userId==1L })
        assertEquals("RemovePlayer",bot.service.history(creator,trainingId="t").items.first().kind)
    }

    @Test fun `Telegram selection adds a real external account once and returns to the add list`() {
        setup();click("Добавить игрока");click("Добавить через Telegram")
        val form=bot.state.form(1,1)!!
        val update=TgUpdate(sequence++,TgMessage(sequence,TgChat(1,"private"),TgUser(1,firstName="Игрок 1"),
            usersShared=TgUsersShared(form.request,listOf(TgSharedUser(99,"Гость")))))
        bot.handle(update);bot.handle(update)
        assertEquals(99,training().players.single().userId)
        assertTrue(training().players.single().playing)
        assertTrue(rows().flatten().any { it.endsWith("Добавить через Telegram") })
        assertFalse(rows().flatten().contains("Гость"))
        assertNull(bot.state.form(1,1))
        assertEquals(1,bot.service.history(creator,trainingId="t").items.count { it.kind=="AddPlayers" })
    }

    @Test fun `creator status transitions reverse only training balances and reject stale changes`() {
        setup()
        fun run(c:SettlementCommand)=bot.service.execute(creator,"seed${sequence++}",c)
        run(SettlementCommand.AddPlayers("t",training().version,listOf(1,3)))
        for(u in listOf(1L,3L)) run(SettlementCommand.ChangeAttendance("t",u,AttendanceChange.ADJUST_MINUTES,60))
        run(SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300))
        run(SettlementCommand.RecordTransfer("transfer",3,1,50,"2026-09-13"))
        openEditor();click("Изменить статус")
        assertEquals(listOf("🚫 Отменена","✅ Завершена","⬅️ Назад"),rows().flatten())
        val stale=latest();click("Завершена")
        assertEquals(mapOf(1L to 100L,3L to -100L),bot.service.balances(creator))
        assertFailsWith<AccountingException> { run(SettlementCommand.CancelTraining("t",training().version)) }
        click("Отменена",source=stale);assertEquals(TrainingPhase.CLOSED,training().phase)
        click("Изменить статус");assertEquals(listOf("🔓 Открыта","⬅️ Назад"),rows().flatten());click("Открыта")
        assertEquals(mapOf(1L to -50L,3L to 50L),bot.service.balances(creator))
        status("Отменена");assertEquals(mapOf(1L to -50L,3L to 50L),bot.service.balances(creator))
        click("Изменить статус");val replay=click("Открыта");bot.handle(replay)
        status("Завершена");assertEquals(mapOf(1L to 100L,3L to -100L),bot.service.balances(creator))
    }

    @Test fun `managed stale controls and revoked editor rights cannot mutate training`() {
        setup();click("Добавить игрока");click("Игрок 3");click("⬅️ Назад")
        click("Управление игроками");click("Игрок 3");val root=latest()
        click("Время · 0 ч");val time=latest();click("+1 ч");click("⬅️ Назад");click("Оплата · 0 ₽");val paid=latest()
        bot.service.execute(creator,"cancel",SettlementCommand.CancelTraining("t",training().version))
        val before=training()
        for((label,source) in listOf("Добавить гостя" to root,"Время · 0 ч" to root,"+1 ч" to time,"+100 ₽" to paid)) {
            click(label,source=source);assertEquals("Тренировка отменена",alerts.last())
        }
        assertEquals(before,training())
        openEditor(2);click("Изменить статус",2);val saved=latest(2)
        fake.members[-1L to 2L]=TgMember("member")
        click("Открыта",2,saved);assertEquals(before,training());assertTrue(alerts.last().contains("администратор"))
    }
}
