package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class SelfServiceBotTest {
    @TempDir lateinit var dir: Path
    private val fake = FakeTelegramApi()
    private val ephemeralMessages = mutableMapOf<Pair<Long, Long>, TgMessage>()
    private val deletedEphemerals = mutableListOf<Triple<Long,Long,Long>>()
    private var nextEphemeral = 700L
    private val answers = mutableListOf<String>()
    private val answerAlerts = mutableListOf<Boolean>()
    private var clock = object : Clock() {
        var now = Instant.parse("2026-09-09T16:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
        override fun instant() = now
    }
    private val api = object : TelegramApi by fake {
        override fun ephemeralRich(chatId:Long,userId:Long,callbackId:String,text:String,html:String,keyboard:TgKeyboard) = ephemeral(chatId,userId,callbackId,text,keyboard)
        override fun editEphemeralRich(chatId:Long,userId:Long,ephemeralId:Long,text:String,html:String,keyboard:TgKeyboard) = editEphemeral(chatId,userId,ephemeralId,text,keyboard)

        override fun ephemeral(chatId: Long, userId: Long, callbackId: String, text: String, keyboard: TgKeyboard): TgMessage =
            TgMessage(chat = TgChat(chatId, "supergroup"), from = fake.bot, text = text, keyboard = keyboard,
                receiver = TgUser(userId, firstName = "User $userId"), ephemeralId = nextEphemeral++).also { ephemeralMessages[chatId to userId] = it }
        override fun editEphemeral(chatId: Long, userId: Long, ephemeralId: Long, text: String, keyboard: TgKeyboard) {
            val old = ephemeralMessages[chatId to userId] ?: throw TelegramFailure(FailureKind.MESSAGE_MISSING,400)
            assertEquals(old.ephemeralId, ephemeralId)
            ephemeralMessages[chatId to userId] = old.copy(text = text, keyboard = keyboard)
        }
        override fun answer(callbackId: String, text: String?, alert: Boolean) { if (text != null) { answers += text; answerAlerts += alert } }
        override fun requestUsers(chatId: Long, text: String, requestId: Int) = fake.send(chatId, text, null, false)
        override fun deleteEphemeral(chatId: Long, userId: Long, ephemeralId: Long) {
            deletedEphemerals += Triple(chatId,userId,ephemeralId)
            if (ephemeralMessages[chatId to userId]?.ephemeralId==ephemeralId) ephemeralMessages.remove(chatId to userId)
        }
    }
    private lateinit var bot: SelfServiceBot
    private var updateId = 1L
    private fun setup(trace:((String)->Unit)?=null) {
        bot = SelfServiceBot(api, Database(dir.resolve("new.sqlite"),trace), fake.bot, clock)
        for (id in 1L..4L) bot.service.remember(Account(id, "User $id"))
        for (group in listOf(-1L, -2L)) {
            bot.service.register(SettlementGroup(group, if (group == -1L) "Первая группа" else "Вторая группа", "Europe/Moscow"))
            for (user in 1L..3L) {
                bot.service.rememberMembership(group, user, true)
                fake.members[group to user] = TgMember(if (group == -1L && user == 1L || group == -2L && user == 3L) "administrator" else "member")
            }
        }
    }
    private fun message(user: Long, text: String, chat: Long = user): TgUpdate {
        val update = TgUpdate(updateId++, TgMessage(1000 + updateId, TgChat(chat, if (chat < 0) "supergroup" else "private", if (chat == -1L) "Первая группа" else null), TgUser(user, firstName = "User $user"), text))
        bot.handle(update)
        return update
    }
    private fun crashAfterReply(text:String):TgUpdate {
        val update=TgUpdate(updateId++,TgMessage(3000+updateId,TgChat(1,"private"),TgUser(1,firstName="User 1"),text))
        bot.state.database.write { c -> c.createStatement().use {
            it.execute("CREATE TRIGGER test_completion_failure BEFORE UPDATE OF completed ON bot_events WHEN NEW.update_id=${update.id} AND NEW.completed=1 BEGIN SELECT RAISE(ABORT,'simulated crash before completion'); END")
        } }
        assertFailsWith<java.sql.SQLException> { bot.handle(update) }
        bot.state.database.write { c -> c.createStatement().use { it.execute("DROP TRIGGER test_completion_failure") } }
        assertFalse(bot.state.completed(update.id));assertNotNull(bot.state.plan(update.id))
        return update
    }
    private fun latest(user: Long) = fake.messages.values.last { it.chat.id == user }
    private fun click(user: Long, label: String, message: TgMessage = latest(user)): TgUpdate {
        val button = requireNotNull(message.keyboard).rows.flatten().firstOrNull { it.text.contains(label) } ?: error("Button $label not found: ${message.keyboard.rows.flatten().map { it.text }}")
        val update = TgUpdate(updateId++, callback = TgCallback("cb:$updateId", TgUser(user, firstName = "User $user"), message, button.callbackData))
        bot.handle(update)
        return update
    }
    private fun open(user: Long, group: String = "Первая") { message(user, "/start"); click(user, group) }
    private fun create(user:Long=1) {
        open(user)
        click(user, "Создать тренировку")
        click(user, "Оставить")
        click(user, "09.09.2026")
        click(user, "18:30")
        click(user, "Опубликовать")
        bot.maintain()
    }
    private fun publicCard() = fake.messages.values.single { it.chat.id == -1L }
    private fun panelClick(user:Long,label:String)=click(user,label,ephemeralMessages.getValue(-1L to user))
    private fun join(user:Long,minutes:Long=0) {
        click(user,"Открыть",publicCard());panelClick(user,"Присоединиться")
        if(minutes>0) {
            panelClick(user,"Время")
            repeat((minutes/30).toInt()) { panelClick(user,"+0,5 ч") }
            panelClick(user,"Назад")
        }
    }
    private fun pay(user:Long,amount:Long=300) {
        panelClick(user,"Оплата")
        var rest=amount
        for(step in listOf(100L,50L,5L)) {
            repeat((rest/step).toInt()) { panelClick(user,"+$step ₽") };rest%=step
        }
        panelClick(user,"Назад")
    }
    private fun closeParticipation(user:Long) { panelClick(user,"Закрыть");bot.maintain() }


    @Test fun `member creates without playing finds training in mine and finishes while other member cannot`() {
        setup();open(2);click(2,"Создать тренировку");click(2,"Оставить")
        click(2,"09.09.2026");click(2,"18:30");click(2,"Опубликовать");bot.maintain()
        val id=bot.service.trainings(Access(-1,2),mine=true).items.single().id
        assertEquals(2,bot.service.training(Access(-1,2),id).createdBy)
        open(2);click(2,"Мои тренировки");click(2,"09.09.2026")
        assertTrue(latest(2).keyboard!!.rows.flatten().any { it.text.contains("Учесть тренировку") })
        assertTrue(latest(2).keyboard!!.rows.flatten().any { it.text=="Игроки" })
        join(3,60);pay(3,355)
        assertEquals(355,bot.service.training(Access(-1,3),id).players.single().paid)
        panelClick(3,"Оплата");panelClick(3,"−5 ₽");panelClick(3,"−50 ₽");panelClick(3,"−100 ₽")
        assertEquals(200,bot.service.training(Access(-1,3),id).players.single().paid)
        assertFalse(ephemeralMessages.getValue(-1L to 3L).keyboard!!.rows.flatten().any { it.text.contains("Учесть") })
        val foreign=bot.screens.render(ScreenAction("training",-1,id),Access(-1,3),"foreign",3)
        assertFalse(foreign.keyboard.rows.flatten().any { it.text.contains("Учесть") })
        assertFailsWith<ru.movereon.tennis.core.AccountingException> { bot.screens.render(ScreenAction("preview_finish",-1,id),Access(-1,3),"forged",3) }
        click(2,"Открыть",publicCard())
        assertFalse(ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.flatten().any { it.text.contains("Учесть тренировку") || it.text.contains("Редактировать") })
        click(2,"Учесть тренировку");click(2,"Подтвердить учёт");bot.maintain()
        assertEquals(TrainingPhase.CLOSED,bot.service.training(Access(-1,2),id).phase)
        assertEquals(0,bot.service.trainings(Access(-1,2),mine=true).total)
        assertEquals(1,bot.service.trainings(Access(-1,3),mine=true).total)
    }

    @Test fun `creator loses closing permission after leaving even with an open confirmation`() {
        setup();create();join(2,60);pay(2);click(1,"Учесть тренировку")
        val id=bot.service.trainings(Access(-1,1)).items.single().id
        fake.members[-1L to 1L]=TgMember("left")
        click(1,"Подтвердить учёт")
        assertEquals(TrainingPhase.OPEN,bot.service.training(Access(-1,2),id).phase)
    }

    @Test fun `accounted and cancelled cards are unpinned once without removing messages or other pins`() {
        setup();create();join(2,60);pay(2)
        val message=publicCard().id
        fake.pinned+=-1L to 999L;fake.pinned+=-2L to message
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт");bot.maintain()
        assertEquals(listOf(-1L to message),fake.unpinned)
        assertEquals(message,publicCard().id)
        assertEquals("UNPINNED",bot.state.pinStatus("training:-1:"+bot.service.trainings(Access(-1,1)).items.single().id))
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(1,fake.unpinned.size)
        click(1,"Отменить тренировку");click(1,"Да, отменить");bot.maintain()
        assertEquals(1,fake.unpinned.size)
        click(1,"Восстановить тренировку");bot.maintain()
        assertEquals(3,fake.pinned.size,"Restoring does not notify or pin again")
        click(1,"Отменить тренировку");click(1,"Да, отменить");bot.maintain()
        assertEquals(2,fake.unpinned.size)
    }

    @Test fun `old closed cards and interrupted unpin requests recover without repeating pins`() {
        setup();create();val auth=Access(-1,1,true);val t=bot.service.trainings(auth).items.single()
        bot.service.execute(auth,"cancel-old",SettlementCommand.CancelTraining(t.id,t.version))
        val key="training:-1:${t.id}";bot.state.pinStatus(key,"NONE")
        fake.unpinFailure=TelegramFailure(FailureKind.UNCERTAIN)
        assertFailsWith<TelegramFailure> { bot.maintain() };assertEquals("UNPIN_PENDING",bot.state.pinStatus(key))
        bot.state.pinStatus(key,"UNPIN_SENDING")
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        fake.unpinFailure=null;bot.maintain();bot.maintain()
        assertEquals(listOf(-1L to publicCard().id),fake.unpinned);assertEquals(1,fake.pinAttempts)
    }

    @Test fun `rejected unpin has an admin retry and cannot repin a cancelled card`() {
        setup();create();fake.unpinFailure=TelegramFailure(FailureKind.REJECTED,403)
        click(1,"Отменить тренировку");click(1,"Да, отменить");bot.maintain()
        val count=fake.unpinAttempts.size;bot.maintain();assertEquals(count,fake.unpinAttempts.size)
        open(1);click(1,"Управление тренировками");click(1,"09.09.2026")
        assertTrue(latest(1).text!!.contains("Не удалось снять"))
        fake.unpinFailure=null;click(1,"Повторить снятие закрепа")
        assertEquals(1,fake.unpinned.size);assertEquals(1,fake.pinAttempts)
    }

    @Test fun `a cancelled card with a pending pin is never pinned`() {
        setup();create();val t=bot.service.trainings(Access(-1,1)).items.single()
        bot.state.pinStatus("training:-1:${t.id}","PENDING")
        bot.service.execute(Access(-1,1,true),"cancel-pending",SettlementCommand.CancelTraining(t.id,t.version));bot.maintain()
        assertEquals(1,fake.pinAttempts);assertEquals(1,fake.unpinned.size)
    }

    @Test fun `private menu cleanup survives restart and too old menus become short inactive messages`() {
        setup();create();val old=latest(1)
        fake.deleteFailure=TelegramFailure(FailureKind.UNCERTAIN)
        message(1,"/start");val current=latest(1)
        assertNotEquals(old.id,current.id);assertNotNull(fake.messages[1L to old.id])
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        fake.deleteFailure=TelegramFailure(FailureKind.REJECTED,400);bot.maintain()
        assertEquals("Меню обновлено. Используй последнее сообщение бота.",fake.messages[1L to old.id]!!.text)
        assertTrue(fake.messages[1L to old.id]!!.keyboard!!.rows.isEmpty())
        assertEquals(current.id,latest(1).id);assertTrue(bot.state.retiredPrivateMenus().isEmpty())
        fake.deleteFailure=null;open(1);click(1,"Управление тренировками");click(1,"09.09.2026")
        val id=latest(1).id
        repeat(3) { click(1,"В меню группы");click(1,"Управление тренировками");click(1,"09.09.2026") }
        assertEquals(id,latest(1).id)
    }

    @Test fun `date arrows clamp calendar boundaries and clock buttons wrap within the selected day`() {
        setup();open(2);click(2,"Создать тренировку");click(2,"Оставить")
        val dateForm=bot.state.form(2,2)!!
        bot.state.session(2,2,-1,dateForm.copy(date="2028-01-31"))
        // Render through the fresh form so its signature matches the adjusted fixture date.
        message(2,"/start");click(2,"Продолжить ввод")
        click(2,"▲ Месяц");assertEquals("2028-02-29",bot.state.form(2,2)!!.date)
        click(2,"▲ Год");assertEquals("2029-02-28",bot.state.form(2,2)!!.date)
        click(2,"▲ День");assertEquals("2029-03-01",bot.state.form(2,2)!!.date)
        click(2,"▼ День");assertEquals("2029-02-28",bot.state.form(2,2)!!.date)
        click(2,"Продолжить ·");assertTrue(latest(2).text!!.contains("18:30"))
        assertEquals(listOf(listOf("▲ Часы","▲ Минуты"),listOf("18","30"),listOf("▼ Часы","▼ Минуты")),
            latest(2).keyboard!!.rows.take(3).map { row -> row.map { it.text } })
        val unchanged=bot.state.form(2,2)!!
        click(2,"18");click(2,"30")
        assertEquals(unchanged,bot.state.form(2,2))
        val selectedDay=bot.state.form(2,2)!!.date
        repeat(5) { click(2,"▲ Часы") };click(2,"▲ Минуты")
        assertEquals("00:00",bot.state.form(2,2)!!.time);assertEquals(selectedDay,bot.state.form(2,2)!!.date)
        click(2,"▼ Минуты");click(2,"▼ Часы");assertEquals("22:30",bot.state.form(2,2)!!.time)
        click(2,"Продолжить ·");click(2,"Опубликовать")
        assertEquals("22:30",bot.service.trainings(Access(-1,2)).items.single().startTime)
    }

    @Test fun `admins set a group default for new trainings without changing existing ones`() {
        setup();create();val existing=bot.service.trainings(Access(-1,1)).items.single()
        assertEquals("18:30",existing.startTime)
        open(1);click(1,"Настройки группы");click(1,"Начало по умолчанию");click(1,"▲ Часы");click(1,"▲ Минуты");click(1,"Сохранить время")
        assertEquals("20:00",bot.service.group(-1).defaultStartTime)
        assertEquals("18:30",bot.service.group(-2).defaultStartTime)
        assertEquals(existing,bot.service.training(Access(-1,1),existing.id))
        open(2);assertFalse(latest(2).keyboard!!.rows.flatten().any { it.text.contains("Настройки") })
        click(2,"Создать тренировку");click(2,"Оставить");click(2,"09.09.2026")
        assertTrue(latest(2).text!!.contains("20:00"))
    }

    @Test fun `old private menus discovered by stale callbacks are retired without deleting current menu`() {
        setup();open(1);val old=latest(1);message(1,"/start");val current=latest(1)
        fake.messages[1L to old.id]=old
        clock.now=clock.now.plusSeconds(121);bot.state.cleanup()
        click(1,"Мои тренировки",old)
        assertNull(fake.messages[1L to old.id]);assertNotNull(fake.messages[1L to current.id])
    }

    @Test fun `restore cancels a delayed unpin and missing messages finish cleanup`() {
        setup();create();val auth=Access(-1,1,true);val t=bot.service.trainings(auth).items.single();val key="training:-1:${t.id}"
        bot.service.execute(auth,"cancel",SettlementCommand.CancelTraining(t.id,t.version));bot.state.reconcilePins()
        assertEquals("UNPIN_PENDING",bot.state.pinStatus(key))
        bot.service.execute(auth,"restore",SettlementCommand.RestoreTraining(t.id,bot.service.training(auth,t.id).version));bot.maintain()
        assertTrue(fake.unpinAttempts.isEmpty());assertEquals(1,fake.pinAttempts)
        bot.service.execute(auth,"cancel-again",SettlementCommand.CancelTraining(t.id,bot.service.training(auth,t.id).version))
        fake.unpinFailure=TelegramFailure(FailureKind.MESSAGE_MISSING,400);bot.maintain()
        assertEquals("UNPINNED",bot.state.pinStatus(key));bot.maintain();assertEquals(1,fake.unpinAttempts.size)
    }

    @Test fun `personal controls follow the mockup with direct guests and compact submenu navigation`() {
        setup();create();join(2)
        fun rows()=ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.map { row -> row.map { it.text } }
        assertEquals(listOf(listOf("Оплата · 0 ₽"),listOf("Время · 0 ч"),listOf("Добавить гостя"),listOf("Не участвую"),listOf("Закрыть")),rows())
        panelClick(2,"Добавить гостя")
        assertEquals(listOf("Добавить гостя","Убрать гостя"),rows()[2])
        assertEquals(1,bot.service.trainings(Access(-1,2)).items.single().players.single().guestCount)
        panelClick(2,"Убрать гостя")
        assertEquals(listOf("Добавить гостя"),rows()[2])
        panelClick(2,"Оплата")
        assertEquals(listOf(listOf("+5 ₽","+50 ₽","+100 ₽"),listOf("−5 ₽","−50 ₽","−100 ₽"),listOf("⬅️ Назад","Закрыть")),rows())
        panelClick(2,"Назад");panelClick(2,"Время")
        assertEquals(listOf(listOf("+0,5 ч","+1 ч"),listOf("−0,5 ч","−1 ч"),listOf("⬅️ Назад","Закрыть")),rows())
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.contains("Статус: Открыта"))
    }

    @Test fun `admin can correct a non-playing payers amount without rejoining them`() {
        setup();create();join(2,60);pay(2);panelClick(2,"Не участвую")
        click(1,"Игроки");click(1,"User 2");click(1,"Другая сумма");message(1,"450");click(1,"Всё правильно")
        val player=bot.service.trainings(Access(-1,1)).items.single().players.single()
        assertFalse(player.playing);assertEquals(450,player.paid);assertEquals(0,player.minutes)
    }

    @Test fun `payment increment overflow reports an error without crashing or changing financial data`() {
        setup();create();join(2,60)
        val auth=Access(-1,1,true);val id=bot.service.trainings(auth).items.single().id
        bot.service.execute(auth,"maximum",SettlementCommand.ChangeAttendance(id,2,AttendanceChange.SET_PAID,Long.MAX_VALUE));bot.maintain()
        val before=bot.service.training(auth,id);val history=bot.service.history(auth).total
        panelClick(2,"Оплата");panelClick(2,"+100 ₽")
        assertTrue(answers.last().contains("Слишком большое"))
        assertEquals(before,bot.service.training(auth,id));assertEquals(history,bot.service.history(auth).total)
    }

    @Test fun `multiple guests share inviters time and tables show separate rounded balances`() {
        setup();create();join(1,60);pay(1,750);join(2,60)
        panelClick(2,"Добавить гостя");panelClick(2,"Добавить гостя");bot.maintain()
        val auth=Access(-1,1,true);val t=bot.service.trainings(auth).items.single()
        assertEquals(2,t.players.single { it.userId==2L }.guestCount)
        assertEquals(60,t.players.single { it.userId==2L }.guestMinutes)
        val html=fake.richMessages.getValue(-1L to publicCard().id)
        assertTrue(html.contains("User 2 гость 1"));assertTrue(html.contains("User 2 гость 2"))
        assertTrue(html.contains("tg://user?id=2"))
        assertTrue(publicCard().text!!.contains("User 2 | 1 ч | 0 ₽ | -188 ₽"))
        assertTrue(bot.service.balances(auth).isEmpty())
        panelClick(2,"Время");panelClick(2,"+0,5 ч");bot.maintain()
        val changed=bot.service.training(auth,t.id).players.single { it.userId==2L }
        assertEquals(90,changed.minutes);assertEquals(90,changed.guestMinutes)
        assertEquals(publicCard().text,ephemeralMessages.getValue(-1L to 1L).text)
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт")
        assertEquals(mapOf(1L to 614L,2L to -614L),bot.service.balances(auth))
    }

    @Test fun `accounted training closes member panels and stale buttons cannot reopen or edit it`() {
        setup();create();join(2,60);pay(2);click(3,"Открыть",publicCard());click(1,"Открыть",publicCard())
        val stale=ephemeralMessages.getValue(-1L to 2L)
        assertFalse(ephemeralMessages.getValue(-1L to 1L).keyboard!!.rows.flatten().any { it.text.contains("Редактировать") })
        assertFalse(stale.keyboard!!.rows.flatten().any { it.text.contains("Редактировать") })
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт");bot.maintain()
        assertFalse(ephemeralMessages.containsKey(-1L to 2L));assertFalse(ephemeralMessages.containsKey(-1L to 3L))
        val before=bot.service.trainings(Access(-1,1)).items.single()
        click(2,"Не участвую",stale);click(2,"Открыть",publicCard())
        assertTrue(answers.last().contains("завершена"));assertFalse(ephemeralMessages.containsKey(-1L to 2L))
        assertEquals(before,bot.service.training(Access(-1,1),before.id))
        assertFalse(ephemeralMessages.containsKey(-1L to 1L))
    }

    @Test fun `closed training rejects all leftover participant controls including creator and admin`() = finalizedControls(false)
    @Test fun `cancelled training rejects all leftover participant controls including creator and admin`() = finalizedControls(true)

    private fun finalizedControls(cancelled:Boolean) {
        setup();create(2)
        val panels=mutableMapOf<Long,List<TgMessage>>()
        for(user in 1L..3L) {
            join(user,30)
            val main=ephemeralMessages.getValue(-1L to user)
            panelClick(user,"Время");val time=ephemeralMessages.getValue(-1L to user)
            panelClick(user,"Назад");panelClick(user,"Оплата");val paid=ephemeralMessages.getValue(-1L to user)
            panels[user]=listOf(main,time,paid)
        }
        panelClick(2,"Назад");pay(2)
        val auth=Access(-1,1,true);val t=bot.service.trainings(auth).items.single()
        bot.service.execute(auth,"finalize",if(cancelled) SettlementCommand.CancelTraining(t.id,t.version) else SettlementCommand.FinishTraining(t.id,t.version))
        val before=bot.service.training(auth,t.id);val history=bot.service.history(auth).total
        val expected=if(cancelled) "Тренировка отменена" else "Тренировка завершена"
        for(user in 1L..3L) {
            val (main,time,paid)=panels.getValue(user)
            val attempts=listOf("Открыть" to publicCard(),"Время" to main,"Оплата" to main,"Добавить гостя" to main,
                "+0,5 ч" to time,"−0,5 ч" to time,"+5 ₽" to paid,"−5 ₽" to paid,"Назад" to time)
            for((label,message) in attempts) {
                click(user,label,message)
                assertEquals(expected,answers.last(),"$user: $label")
                assertTrue(answerAlerts.last(),"An alert with OK must be shown")
            }
        }
        assertEquals(before,bot.service.training(auth,t.id));assertEquals(history,bot.service.history(auth).total)
        bot.maintain()
        assertTrue(ephemeralMessages.isEmpty())
    }

    @Test fun `public edit link grants creator access only to their own training`() {
        setup();create(2)
        val card=publicCard()
        assertEquals(listOf(listOf("Открыть","Редактировать")),card.keyboard!!.rows.map { row->row.map { it.text } })
        val start="/start "+card.keyboard!!.rows.flatten().single { it.text=="Редактировать" }.url!!.substringAfter("start=")
        message(3,start)
        assertTrue(latest(3).text!!.contains("создатель"))
        assertFalse(latest(3).keyboard!!.rows.flatten().any { it.text=="Игроки" })
        message(2,start);click(2,"Изменить название и время");message(2,"Новое название")
        click(2,"Продолжить ·");click(2,"Продолжить ·");click(2,"Сохранить изменения")
        assertEquals("Новое название",bot.service.trainings(Access(-1,2)).items.single().title)
        click(2,"Игроки");click(2,"Участники группы");click(2,"User 3");click(2,"Добавить ·")
        click(2,"User 3");click(2,"+0,5 ч");click(2,"Всё правильно")
        assertEquals(30,bot.service.trainings(Access(-1,2)).items.single().players.single { it.userId==3L }.minutes)
        message(1,start);assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Игроки" })
        fake.members[-1L to 2L]=TgMember("left");message(2,start)
        assertFalse(latest(2).keyboard!!.rows.flatten().any { it.text=="Игроки" })
    }

    @Test fun `refresh of a deleted closed card does not publish a historical message again`() {
        setup();create();join(2,60);pay(2)
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт");bot.maintain()
        val card=publicCard();fake.delete(-1,card.id)
        val sent=fake.sent.size
        bot.service.database.write { sqlUpdate(it,"UPDATE actions SET delivered_at=NULL WHERE training_id IS NOT NULL") }
        bot.maintain()
        assertEquals(sent,fake.sent.size)
        assertTrue(bot.state.pendingCards().isEmpty())
        assertTrue(fake.messages.values.none { it.chat.id==-1L })
    }

    @Test fun `complete training roster survives updates and restart without pagination or duplicate pins`() {
        setup();create();val auth=Access(-1,1,true);val id=bot.service.trainings(auth).items.single().id
        val users=(5L..31L).toList()
        users.forEach { bot.service.remember(Account(it,"Игрок $it"));bot.service.rememberMembership(-1,it,true) }
        bot.service.execute(auth,"add",SettlementCommand.AddPlayers(id,1,users));bot.maintain()
        val message=publicCard().id
        fun completeCard() {
            assertEquals(message,publicCard().id)
            assertFalse(publicCard().text!!.contains("Страница"))
            assertEquals(listOf("Открыть","Редактировать"),publicCard().keyboard!!.rows.flatten().map { it.text })
            assertEquals(28,Regex("<tr>").findAll(fake.richMessages.getValue(-1L to message)).count())
            users.forEach { assertTrue(publicCard().text!!.contains("Игрок $it |")) }
        }
        completeCard()
        bot.service.execute(auth,"paid",SettlementCommand.ChangeAttendance(id,5,AttendanceChange.SET_PAID,100));bot.maintain()
        completeCard();assertTrue(publicCard().text!!.contains("Игрок 5 | 0 ч | 100 ₽"))
        // A saved page from the previous release cannot hide any players after restart.
        bot.state.displayPage("training:-1:$id",3)
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        completeCard();assertEquals(1,fake.pinned.size)
        click(2,"Открыть",publicCard())
        assertEquals(publicCard().text,ephemeralMessages.getValue(-1L to 2L).text)
        assertFalse(ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.flatten().any { it.text.contains("Дальше") })
    }

    @Test fun `40 players with guests fit in one rich card across training states`() {
        setup();create();val auth=Access(-1,1,true);val original=bot.service.trainings(auth).items.single()
        val users=(5L..44L).toList()
        users.forEach { bot.service.remember(Account(it,"Участник $it " + "ДлинноеИмя".repeat(6)));bot.service.rememberMembership(-1,it,true) }
        val players=users.mapIndexed { i,user -> Attendance(user,true,60,60,paid=if(i==0) 400 else 0,ordinal=i,guestCount=1) }
        for(phase in TrainingPhase.entries) {
            val content=TrainingCard.render(original.copy(players=players,phase=phase),bot.service::account)
            assertEquals(81,Regex("<tr>").findAll(content.html).count())
            assertTrue(content.text.length>4096);assertTrue(content.text.length<=32768)
            assertFalse(content.text.contains("Страница"))
            users.forEach { assertTrue(content.html.contains("tg://user?id=$it")) }
        }
        bot.service.execute(auth,"add",SettlementCommand.AddPlayers(original.id,1,users))
        users.forEach { bot.service.execute(auth,"guest:$it",SettlementCommand.ChangeAttendance(original.id,it,AttendanceChange.ADJUST_GUESTS,1)) }
        bot.maintain()
        assertTrue(publicCard().text!!.length>4096)
        val privateCard=bot.screens.render(ScreenAction("training",-1,original.id,page=12),auth,"test:full",1)
        assertEquals(publicCard().text,privateCard.text)
        click(2,"Открыть",publicCard())
        assertEquals(publicCard().text,ephemeralMessages.getValue(-1L to 2L).text)
    }

    @Test fun `pin rejection is visible to admin and retry pins only the existing card`() {
        setup();fake.pinFailure=TelegramFailure(FailureKind.REJECTED,400);create()
        val id=bot.service.trainings(Access(-1,1)).items.single().id
        assertEquals("FAILED",bot.state.pinStatus("training:-1:$id"))
        open(1);click(1,"Управление тренировками");click(1,"09.09.2026")
        assertTrue(latest(1).text!!.contains("право бота закреплять"))
        fake.pinFailure=null;click(1,"Повторить закрепление")
        assertEquals(listOf(-1L to publicCard().id),fake.pinned)
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(1,fake.pinned.size);assertEquals(1,fake.sent.count { it.chat.id==-1L })
    }

    @Test fun `uncertain pin is not retried automatically after restart`() {
        setup();fake.pinFailure=TelegramFailure(FailureKind.UNCERTAIN);create()
        val id=bot.service.trainings(Access(-1,1)).items.single().id
        assertEquals("UNKNOWN",bot.state.pinStatus("training:-1:$id"));assertEquals(1,fake.pinAttempts)
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain();bot.maintain()
        assertEquals(1,fake.pinAttempts)
    }

    @Test fun `rich table escapes user text instead of interpreting it as HTML`() {
        setup();create();join(2,60)
        val auth=Access(-1,1,true);val t=bot.service.trainings(auth).items.single()
        bot.service.remember(Account(2,"<script>& чужое имя"))
        bot.service.execute(auth,"title",SettlementCommand.EditTraining(t.id,t.version,"<b> & название","2026-09-09","19:00"));bot.maintain()
        val html=fake.richMessages.getValue(-1L to publicCard().id)
        assertTrue(html.contains("&lt;b&gt; &amp; название"));assertTrue(html.contains("&lt;script&gt;&amp; чужое имя"))
        assertFalse(html.contains("<script>"))
    }

    @Test fun `admin creates in private and group members use personal controls without public spam`() {
        setup();create()
        val card=publicCard()
        assertFalse(card.text!!.contains("Первая группа"))
        assertTrue(card.text!!.contains("Пока никто не зарегался"))
        assertEquals(listOf("Открыть","Редактировать"),card.keyboard!!.rows.flatten().map { it.text })
        assertEquals(listOf(-1L to card.id),fake.pinned)
        click(2,"Открыть",card)
        assertEquals(card.text,ephemeralMessages.getValue(-1L to 2L).text)
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty())
        panelClick(2,"Присоединиться")
        assertEquals(0,bot.service.trainings(Access(-1,2)).items.single().players.single().minutes)
        pay(2,300);panelClick(2,"Оплата")
        val delta=panelClick(2,"+100 ₽");bot.handle(delta)
        panelClick(2,"Назад");panelClick(2,"Время");panelClick(2,"+1 ч");panelClick(2,"+0,5 ч");bot.maintain()
        val row=bot.service.trainings(Access(-1,2)).items.single().players.single()
        assertEquals(400,row.paid);assertEquals(90,row.minutes)
        assertNull(bot.state.attendanceDraft(2,-1))
        assertEquals(publicCard().text,ephemeralMessages.getValue(-1L to 2L).text)
        assertTrue(fake.richMessages.getValue(-1L to card.id).contains("<table"))
        assertEquals(1,fake.sent.count { it.chat.id==-1L });assertEquals(1,fake.pinned.size)
    }

    @Test fun `private group choice and public callbacks never inherit another group`() {
        setup();create();open(2,"Вторая");join(2)
        assertEquals(-1,bot.state.selectedGroup(2,-1))
        assertEquals(1,bot.service.trainings(Access(-1,2),mine=true).total)
        assertEquals(0,bot.service.trainings(Access(-2,2),mine=true).total)
        val wrong=publicCard().copy(chat=TgChat(-2,"supergroup","Вторая группа"))
        click(3,"Открыть",wrong)
        assertTrue(answers.last().contains("другой группе"))
        assertEquals(1,bot.service.trainings(Access(-1,1)).items.single().players.size)
    }

    @Test fun `rights are checked live after revocation including saved admin buttons`() {
        setup(); create(3);open(1);click(1,"Управление тренировками");click(1,"09.09.2026")
        val adminCard = latest(1)
        fake.members[-1L to 1L] = TgMember("member")
        click(1, "Учесть тренировку", adminCard)
        assertTrue(answers.last().contains("администратор"))
        val training = bot.service.trainings(Access(-1, 1)).items.single()
        val forged = bot.state.button(ScreenAction("finish", -1, training.id, version = training.version), 2, "personal:2:2")
        bot.handle(TgUpdate(updateId++, callback = TgCallback("forge", TgUser(2), TgMessage(2, TgChat(2, "private")), "n:$forged")))
        assertEquals(TrainingPhase.OPEN, bot.service.training(Access(-1, 2), training.id).phase)
        fake.members[-1L to 2L] = TgMember("left")
        click(2, "Открыть", publicCard())
        assertTrue(answers.last().contains("участникам"))
    }

    @Test fun `one users personal panel cannot be operated by another`() {
        setup();create();click(2,"Открыть",publicCard())
        click(3,"Присоединиться",ephemeralMessages.getValue(-1L to 2L))
        assertTrue(answers.last().contains("другого участника"))
        assertTrue(bot.service.trainings(Access(-1,1)).items.single().players.isEmpty())
        assertNull(bot.state.attendanceDraft(2,-1))
    }

    @Test fun `unknown public send is not automatically repeated and explicit recovery does not duplicate training`() {
        setup()
        fake.acceptThenFail = { chat, _ -> chat == -1L }
        create()
        bot.maintain(); bot.maintain()
        assertEquals(1, fake.sent.count { it.chat.id == -1L })
        message(1, "/start"); click(1, "Первая"); click(1, "Управление тренировками"); click(1, "09.09.2026")
        click(1, "Восстановить сообщение")
        click(1, "Опубликовать карточку заново")
        assertEquals(1, bot.service.trainings(Access(-1, 1)).total)
        assertEquals(2, fake.sent.count { it.chat.id == -1L })
    }

    @Test fun `manual amount and real transfer confirmation work without changing another user`() {
        setup(); create(); open(2)
        click(2,"Мои расчёты"); click(2, "Записать перевод"); click(2, "User 1"); click(2, "Я отправил")
        message(2, "450")
        assertEquals(emptyMap(), bot.service.balances(Access(-1, 2)))
        val recorded = click(2, "Деньги переданы")
        bot.handle(recorded)
        assertEquals(mapOf(1L to -450L, 2L to 450L), bot.service.balances(Access(-1, 2)))
        assertNull(bot.state.form(2, 2))
        click(2, "Уточнить")
        assertEquals(mapOf(1L to 0L, 2L to 0L), bot.service.balances(Access(-1, 2)))
        click(2, "Всё верно")
        assertEquals(mapOf(1L to -450L, 2L to 450L), bot.service.balances(Access(-1, 2)))
    }

    @Test fun `active and permanent buttons survive weeks replaced buttons expire and groups stay separate`() {
        setup();create();click(1,"Открыть",publicCard())
        val card=publicCard();val token=card.keyboard!!.rows.flatten().first().callbackData!!.removePrefix("n:")
        val permanent=publicCard().keyboard!!.rows.flatten().single { it.text.contains("Редактировать") }.url!!.substringAfter("n_")
        val previous=latest(1).keyboard!!.rows.flatten().first().callbackData!!.removePrefix("n:")
        message(1,"/start");clock.now=clock.now.plusSeconds(15*86400);bot.maintain()
        assertNotNull(bot.state.button(token));assertNotNull(bot.state.button(permanent));assertNull(bot.state.button(previous))
        click(2,"Открыть",card);panelClick(2,"Присоединиться")
        assertEquals(1,bot.service.trainings(Access(-1,2)).items.single().players.size)
    }

    @Test fun `27 players fit in a complete card while history and roster stay paged`() {
        setup(); create()
        val a = Access(-1, 1, true)
        val t = bot.service.trainings(a).items.single()
        for (id in 5L..31L) {
            bot.service.remember(Account(id, "Имя".repeat(30), username = "name".repeat(8)))
            bot.service.rememberMembership(-1, id, true)
            bot.service.execute(a, "join:$id", SettlementCommand.ChangeAttendance(t.id, id, AttendanceChange.JOIN))
        }
        bot.maintain()
        for (kind in listOf("history", "roster")) {
            val out = bot.screens.render(ScreenAction(kind, -1, t.id), a, "test:$kind", 1)
            assertTrue(out.text.length <= 4096)
            assertTrue(out.keyboard.rows.flatten().any { it.text == "Дальше ›" })
        }
        assertTrue(publicCard().text!!.length <= 4096)
    }

    @Test fun `unknown private send recovers from delivered callback or explicit start without repeating automatically`() {
        setup()
        fake.acceptThenFail = { chat, _ -> chat == 1L }
        message(1, "/start")
        assertEquals("UNKNOWN", bot.state.delivery("personal:1:1")!!.status)
        click(1, "Первая")
        assertEquals("SENT", bot.state.delivery("personal:1:1")!!.status)
        assertEquals(1, fake.sent.count { it.chat.id == 1L })
    }

    @Test fun `old form confirmation cannot publish a newly edited form`() {
        setup(); open(1); click(1,"Создать тренировку"); click(1,"Оставить")
        click(1,"09.09.2026"); click(1,"18:30")
        val oldPreview = latest(1)
        click(1,"Изменить название")
        message(1,"Новое название"); click(1,"09.09.2026"); click(1,"18:30")
        click(1,"Опубликовать",oldPreview)
        assertEquals(0,bot.service.trainings(Access(-1,1)).total)
        assertTrue(answers.last().contains("Форма изменилась"))
        click(1,"Опубликовать")
        assertEquals("Новое название",bot.service.trainings(Access(-1,1)).items.single().title)
    }

    @Test fun `revoked membership during text input does not crash or apply a payment`() {
        setup();create();click(1,"Игроки");click(1,"Участники группы");click(1,"User 2");click(1,"Добавить ·")
        click(1,"User 2");click(1,"Другая сумма")
        fake.members[-1L to 1L]=TgMember("left");message(1,"1000")
        assertEquals(0,bot.service.trainings(Access(-1,2)).items.single().players.single().paid)
        assertTrue(latest(1).text!!.contains("Доступ только участникам"))
    }

    @Test fun `text input moves the private panel to a new reply while button clicks edit that reply`() {
        setup(); open(1)
        val original = latest(1)
        click(1,"Создать тренировку")
        assertEquals(original.id,latest(1).id)
        val titlePrompt = latest(1)
        message(1,"Вечерний теннис")
        val datePrompt = latest(1)
        assertNotEquals(titlePrompt.id,datePrompt.id)
        assertNull(fake.messages[1L to titlePrompt.id],"The old menu must be removed after the new reply is delivered")
        click(1,"09.09.2026")
        assertEquals(datePrompt.id,latest(1).id)
        message(1,"не время")
        assertNotEquals(datePrompt.id,latest(1).id)
        assertTrue(latest(1).text!!.contains("Проверь формат"))
        val errorReply = latest(1)
        message(1,"/start")
        assertNotEquals(errorReply.id,latest(1).id)
    }

    @Test fun `replaying a text event after delivery does not create another private reply`() {
        setup(); open(1); click(1,"Создать тренировку")
        val update = crashAfterReply("Вечерний теннис")
        val delivered = latest(1)
        val count = fake.sent.size
        bot.handle(update)
        assertEquals(count,fake.sent.size)
        assertEquals(delivered.id,latest(1).id)
    }

    @Test fun `unknown new reply is not resent on replay but new user input gets a fresh reply`() {
        setup(); open(1); click(1,"Создать тренировку")
        fake.acceptThenFail = { chat,_ -> chat==1L }
        val update = crashAfterReply("Вечерний теннис")
        assertEquals("UNKNOWN",bot.state.delivery("personal:1:1")!!.status)
        val count = fake.sent.size
        bot.handle(update)
        assertEquals(count,fake.sent.size)
        message(1,"10.09.2026")
        assertEquals(count+1,fake.sent.size)
        assertEquals("SENT",bot.state.delivery("personal:1:1")!!.status)
    }

    @Test fun `updating the shared card preserves personal time payment and leave controls`() {
        setup();create();join(2,60);pay(2);join(3,60);bot.maintain()
        val panel=ephemeralMessages.getValue(-1L to 2L)
        assertTrue(panel.keyboard!!.rows.flatten().any { it.text.contains("Время") })
        panelClick(2,"Не участвую");bot.maintain()
        val row=bot.service.trainings(Access(-1,1)).items.single().players.single { it.userId==2L }
        assertFalse(row.playing);assertEquals(0,row.minutes);assertEquals(300,row.paid)
        assertTrue(publicCard().text!!.contains("User 2 | 0 ч | 300 ₽ | +300 ₽"))
        assertEquals(publicCard().text,ephemeralMessages.getValue(-1L to 3L).text)
        assertTrue(ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.flatten().any { it.text.contains("Оплата") })
        assertEquals(1,fake.sent.count { it.chat.id==-1L })
    }

    @Test fun `superadmin can appoint and remove a bot admin through the UI in one group only`() {
        setup()
        bot.service.execute(Access(-2,3,true),"other-group-admin",SettlementCommand.SetAdministrator(2,true))
        open(1)
        assertFalse(latest(1).keyboard!!.rows.flatten().any { it.text.contains("Администраторы") })
        click(1,"Настройки группы");click(1,"Администраторы группы")
        click(1,"Назад")
        assertTrue(latest(1).text!!.contains("Настройки группы"))
        click(1,"Администраторы группы")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 1") && it.text.contains("Суперадмин") })
        assertFalse(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 2") })
        click(1,"Назначить администратора")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 3") && it.text.contains("Участник") })
        click(1,"User 2")
        click(1,"Назначить администратором")
        assertTrue(bot.service.isAdmin(Access(-1,2)))
        open(2);click(2,"Настройки группы")
        assertFalse(latest(2).keyboard!!.rows.flatten().any { it.text.contains("Администраторы") })
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Снять назначение" })
        click(1,"Назад")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 2") && it.text.contains("Админ бота") })
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 1") && it.text.contains("Суперадмин") })
        assertFalse(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 3") })
        click(1,"User 2")
        click(1,"Снять назначение")
        assertFalse(bot.service.isAdmin(Access(-1,2)))
        assertTrue(bot.service.isAdmin(Access(-2,2)),"The appointment in another group is independent")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Назначить администратором" })
        click(1,"Назад");click(1,"Назад")
        assertTrue(latest(1).text!!.contains("Настройки группы"))
        open(2)
        assertTrue(latest(2).keyboard!!.rows.flatten().any { it.text=="➕ Создать тренировку" })
    }

    @Test fun `startup refresh updates existing live cards without changing participation or publishing copies`() {
        setup();create();click(2,"Открыть",publicCard());bot.maintain()
        val before=bot.service.trainings(Access(-1,1)).items.single()
        val messageId=publicCard().id
        val sentCount=fake.sent.size
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        bot.maintain()
        assertEquals(before,bot.service.trainings(Access(-1,1)).items.single())
        assertEquals(messageId,publicCard().id)
        assertEquals(sentCount,fake.sent.size)
        assertTrue(publicCard().keyboard!!.rows.flatten().any { it.text=="Открыть" })
    }

    @Test fun `group payment controls use requested steps while admin can type an exact amount`() {
        setup();create();join(2)
        val panel=ephemeralMessages.getValue(-1L to 2L)
        assertFalse(panel.keyboard!!.rows.flatten().any { it.text=="Другая сумма" })
        panelClick(2,"Оплата")
        assertTrue(ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.flatten().map { it.text }.containsAll(listOf("+5 ₽","+50 ₽","+100 ₽","−5 ₽","−50 ₽","−100 ₽")))
        click(1,"Игроки");click(1,"User 2")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Другая сумма" })
    }

    @Test fun `reopening and closing personal panels does not remove another users panel or public card`() {
        setup();create();click(2,"Открыть",publicCard());click(3,"Открыть",publicCard())
        val old=ephemeralMessages.getValue(-1L to 2L);val other=ephemeralMessages.getValue(-1L to 3L)
        click(2,"Открыть",publicCard())
        assertEquals(old.ephemeralId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        panelClick(2,"Закрыть")
        assertNull(bot.state.currentEphemeral(2,-1));assertFalse(ephemeralMessages.containsKey(-1L to 2L))
        assertEquals(other,ephemeralMessages[-1L to 3L]);assertEquals(1,fake.sent.count { it.chat.id==-1L })
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty())
    }

    @Test fun `private navigation uses a direct link and removes the group panel without an explanatory message`() {
        setup();create();click(1,"Открыть",publicCard())
        val link=publicCard().keyboard!!.rows.flatten().single { it.text.contains("Редактировать") }
        assertNull(link.callbackData);assertNotNull(link.url)
        val sent=fake.sent.count { it.chat.id==-1L }
        message(1,"/start ${link.url!!.substringAfter("start=")}")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Игроки" })
        assertFalse(ephemeralMessages.containsKey(-1L to 1L));assertEquals(sent,fake.sent.count { it.chat.id==-1L })
        assertFalse(fake.sent.any { it.text?.contains("Это действие доступно в личном")==true })
    }

    @Test fun `expired personal panel is replaced on the next participation click`() {
        setup();create();click(2,"Открыть",publicCard())
        val expired=ephemeralMessages.remove(-1L to 2L)!!
        click(2,"Открыть",publicCard())
        val fresh=ephemeralMessages.getValue(-1L to 2L)
        assertNotEquals(expired.ephemeralId,fresh.ephemeralId);assertEquals(fresh.ephemeralId,bot.state.currentEphemeral(2,-1))
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty());assertNull(bot.state.attendanceDraft(2,-1))
    }

    @Test fun `admin attendance edits stay atomic and unchanged confirmation records nothing`() {
        setup();create();click(1,"Игроки");click(1,"Участники группы");click(1,"User 2");click(1,"Добавить ·")
        val history=bot.service.history(Access(-1,1)).total
        click(1,"User 2");click(1,"Платил");repeat(4) { click(1,"+50") };click(1,"+0,5 ч")
        assertEquals(history,bot.service.history(Access(-1,1)).total)
        val save=click(1,"Всё правильно");bot.handle(save)
        val saved=bot.service.trainings(Access(-1,1)).items.single()
        assertEquals(500,saved.players.single().paid);assertEquals(30,saved.players.single().minutes)
        assertEquals(history+1,bot.service.history(Access(-1,1)).total)
        click(1,"User 2");click(1,"Всё правильно")
        assertEquals(saved,bot.service.trainings(Access(-1,1)).items.single());assertEquals(history+1,bot.service.history(Access(-1,1)).total)
    }

    @Test fun `saved participation survives restart and switching training reuses one personal panel`() {
        setup();create();join(2,90);pay(2)
        val saved=bot.service.trainings(Access(-1,2)).items.single();val panelId=ephemeralMessages.getValue(-1L to 2L).ephemeralId
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(saved,bot.service.training(Access(-1,2),saved.id));assertEquals(panelId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        bot.service.execute(Access(-1,1,true),"second-training",SettlementCommand.CreateTraining("second","Вторая","2026-09-10","20:00"));bot.maintain()
        val second=fake.messages.values.single { it.chat.id==-1L && it.text!!.startsWith("Вторая") }
        click(2,"Открыть",second)
        assertEquals(panelId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.startsWith("Вторая"))
        assertEquals(saved,bot.service.training(Access(-1,2),saved.id));assertTrue(bot.service.training(Access(-1,2),"second").players.isEmpty())
    }

    @Test fun `confirmed player can edit attendance while another player joins through the same shared card`() {
        setup();create();join(2,60);closeParticipation(2)
        open(2);click(2,"Мои тренировки");click(2,"09.09.2026");click(2,"Открыть участие");click(2,"Время");click(2,"+0,5 ч")
        join(3)
        val t=bot.service.trainings(Access(-1,2)).items.single()
        assertEquals(90,t.players.single { it.userId==2L }.minutes);assertEquals(0,t.players.single { it.userId==3L }.minutes)
        click(2,"Назад");click(2,"Не участвую")
        assertFalse(bot.service.training(Access(-1,2),t.id).players.single { it.userId==2L }.playing)
        click(2,"Присоединиться")
        assertTrue(bot.service.training(Access(-1,2),t.id).players.single { it.userId==2L }.playing)
        assertEquals(0,bot.service.training(Access(-1,2),t.id).players.single { it.userId==2L }.minutes)
    }

    @Test fun `administrator can add a known first time player and an unknown Telegram member without crossing groups`() {
        setup();create();click(1,"Игроки");click(1,"Участники группы")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 2") })
        click(1,"User 2");click(1,"Добавить ·")
        val training=bot.service.trainings(Access(-1,1)).items.single()
        assertTrue(training.players.single().playing)
        assertEquals(0,bot.service.roster(Access(-1,1)).items.single { it.account.id==2L }.attendance)
        click(1,"Участники группы");click(1,"Добавить человека")
        val form=bot.state.form(1,1)!!
        fake.members[-1L to 99L]=TgMember("member")
        bot.handle(TgUpdate(updateId++,TgMessage(2000,TgChat(1,"private"),TgUser(1,firstName="User 1"),
            usersShared=TgUsersShared(form.request,listOf(TgSharedUser(99,"Новичок"))))))
        click(1,"Добавить ·")
        val saved=bot.service.training(Access(-1,1),training.id)
        assertEquals(setOf(2L,99L),saved.players.filter { it.playing }.map { it.userId }.toSet())
        assertTrue(bot.service.roster(Access(-1,1)).items.any { it.account.id==99L })
        assertFalse(bot.service.roster(Access(-2,3)).items.any { it.account.id==99L })
        assertEquals(3,bot.service.history(Access(-1,1)).total)
    }

    @Test fun `bulk selection survives pagination and restart with one confirmed history entry`() {
        setup();create()
        for(id in 5L..28L) {
            bot.service.remember(Account(id,"Player $id"));bot.service.rememberMembership(-1,id,true)
        }
        click(1,"Игроки");click(1,"Участники группы")
        click(1,"Player 10")
        val stale=latest(1)
        click(1,"Дальше")
        val candidate=latest(1).keyboard!!.rows.flatten().first { it.text.startsWith("▫️") }.text
        click(1,candidate)
        val selection=bot.state.form(1,1)!!.selectedUsers
        assertEquals(2,selection.size)
        assertEquals(1,bot.service.history(Access(-1,1)).total)
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        click(1,"Назад")
        assertEquals(selection,bot.state.form(1,1)!!.selectedUsers)
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="✅ Player 10" })
        click(1,"Добавить ·",stale)
        assertTrue(bot.service.trainings(Access(-1,1)).items.single().players.isEmpty())
        val confirmed=click(1,"Добавить · 2")
        bot.handle(confirmed)
        val t=bot.service.trainings(Access(-1,1)).items.single()
        assertEquals(selection.toSet(),t.players.map { it.userId }.toSet())
        assertEquals(2,bot.service.history(Access(-1,1)).total)
        click(1,"Участники группы")
        assertFalse(latest(1).keyboard!!.rows.flatten().any { it.text.contains("Player 10") })
        click(1,"Отмена")
        assertEquals(2,bot.service.history(Access(-1,1)).total)
    }

    @Test fun `concurrent self registration and revoked administrator cannot be overwritten by bulk selection`() {
        setup();create(2);open(1);click(1,"Управление тренировками");click(1,"09.09.2026");click(1,"Игроки");click(1,"Участники группы")
        click(1,"User 2");click(1,"User 3")
        join(2,60);pay(2);closeParticipation(2)
        click(1,"Добавить · 2")
        assertTrue(latest(1).text!!.contains("Состав изменился"))
        assertEquals(listOf(3L),bot.state.form(1,1)!!.selectedUsers)
        fake.members[-1L to 1L]=TgMember("member")
        click(1,"Добавить · 1")
        val t=bot.service.trainings(Access(-1,1)).items.single()
        assertEquals(2L,t.players.single().userId)
        assertEquals(300,t.players.single().paid)
        fake.members[-1L to 1L]=TgMember("administrator")
        click(1,"Добавить · 1")
        assertEquals(2,bot.service.training(Access(-1,1),t.id).players.size)
        click(1,"Участники группы")
        fake.members[-1L to 1L]=TgMember("member")
        message(1,"добавить")
        assertNull(bot.state.form(1,1))
        assertFalse(latest(1).keyboard!!.rows.flatten().any { it.text.contains("Добавить ·") })
    }

    @Test fun `editing a player returns to the same roster page and keeps summary visible`() {
        setup();create()
        val auth=Access(-1,1,true)
        val t=bot.service.trainings(auth).items.single()
        val ids=(5L..16L).toList()
        ids.forEach { bot.service.remember(Account(it,"Player $it"));bot.service.rememberMembership(-1,it,true) }
        bot.service.execute(auth,"many",SettlementCommand.AddPlayers(t.id,t.version,ids))
        click(1,"Игроки");click(1,"Дальше")
        click(1,"Player 13");click(1,"+0,5 ч");click(1,"Платил");click(1,"+1 гость")
        click(1,"Всё правильно")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="2 / 2" })
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Player 13 · 0,5 ч · гостей: 1 · 300 ₽" })
        assertEquals(1,bot.service.training(auth,t.id).players.count { it.paid>0 })
    }

    @Test fun `a page of bulk additions keeps history below Telegram message limit`() {
        setup();create()
        val auth=Access(-1,1,true)
        val id=bot.service.trainings(auth).items.single().id
        repeat(8) { batch ->
            val users=(1L..27L).map { 100+batch*27+it }
            users.forEach { bot.service.remember(Account(it,"Длинное имя ".repeat(10)));bot.service.rememberMembership(-1,it,true) }
            bot.service.execute(auth,"batch:$batch",SettlementCommand.AddPlayers(id,bot.service.training(auth,id).version,users))
        }
        val history=bot.screens.render(ScreenAction("history",-1,id),auth,"history-test",1)
        assertTrue(history.text.length<=4096)
        assertTrue(history.text.contains("Добавил игроков: 27"))
        assertTrue(history.keyboard.rows.flatten().any { it.text=="Дальше ›" })
        assertEquals(216,bot.service.training(auth,id).players.size)
    }

    @Test fun `admin draft cannot overwrite immediate user changes and replay cannot erase a new draft`() {
        setup();create();join(2,60)
        click(1,"Игроки");click(1,"User 2");click(1,"Платил")
        val draft=bot.state.attendanceDraft(1,-1)!!
        pay(2,400)
        click(1,"Всё правильно")
        assertTrue(answers.last().contains("уже изменили"));assertEquals(draft,bot.state.attendanceDraft(1,-1))
        assertEquals(400,bot.service.training(Access(-1,1),draft.training).players.single().paid)
        click(1,"К составу");click(1,"Продолжить ввод");click(1,"Загрузить сохранённые");val save=click(1,"Всё правильно")
        click(1,"User 2");click(1,"+50")
        val fresh=bot.state.attendanceDraft(1,-1);val count=bot.service.history(Access(-1,1)).total
        bot.handle(save)
        assertEquals(fresh,bot.state.attendanceDraft(1,-1));assertEquals(count,bot.service.history(Access(-1,1)).total)
    }

    @Test fun `member menu includes training creation and transfer amount edits preserve review`() {
        setup();bot.service.rememberMembership(-2,2,false);open(2)
        assertEquals(listOf("🏓 Мои тренировки","💰 Мои расчёты","➕ Создать тренировку"),latest(2).keyboard!!.rows.flatten().map { it.text })
        click(2,"Мои расчёты");click(2,"Записать перевод");click(2,"User 1");click(2,"Я получил")
        message(2,"300");click(2,"Деньги переданы")
        val original=bot.service.transfers(Access(-1,2)).items.single()
        click(2,"Уточнить перевод");click(2,"Исправить сумму");message(2,"450");click(2,"Сохранить сумму")
        val edited=bot.service.transfer(Access(-1,2),original.id)
        assertEquals(450,edited.amount);assertEquals(PaymentStatus.REVIEW,edited.status)
        click(2,"История изменений")
        assertTrue(latest(2).text!!.contains("300 ₽ → 450 ₽"));assertFalse(latest(2).text!!.contains(" UTC"))
        click(2,"Назад")
        assertTrue(bot.service.balances(Access(-1,2)).values.all { it==0L })
        click(2,"Всё верно")
        assertEquals(mapOf(1L to 450L,2L to -450L),bot.service.balances(Access(-1,2)))
        click(2,"Назад");click(2,"Назад");click(2,"Баланс группы")
        assertTrue(latest(2).text!!.contains("User 2 · баланс: -450 ₽"))
        assertFalse(Regex("долг|долж",RegexOption.IGNORE_CASE).containsMatchIn(latest(2).text!!))
    }

    @Test fun `back warns on unsaved selection and continues or discards without writing history`() {
        setup();create();click(1,"Игроки");click(1,"Участники группы");click(1,"User 2")
        val selected=bot.state.form(1,1)!!.selectedUsers
        click(1,"Отмена")
        assertTrue(latest(1).text!!.contains("несохранённые"))
        click(1,"Продолжить ввод")
        assertEquals(selected,bot.state.form(1,1)!!.selectedUsers)
        click(1,"Отмена");click(1,"Сбросить и выйти")
        assertNull(bot.state.form(1,1));assertTrue(bot.service.trainings(Access(-1,1)).items.single().players.isEmpty())
        assertEquals(1,bot.service.history(Access(-1,1)).total)
        click(1,"Участники группы");click(1,"Отмена")
        assertFalse(latest(1).text!!.contains("несохранённые"))
    }

    @Test fun `leaving an unsaved admin edit warns and discards only that edit`() {
        setup();create();join(2);join(3)
        click(1,"Игроки");click(1,"User 2");click(1,"Платил")
        click(1,"К составу")
        assertTrue(latest(1).text!!.contains("несохранённые"))
        click(1,"Продолжить ввод");assertEquals(300,bot.state.attendanceDraft(1,-1)!!.value.paid)
        click(1,"К составу");click(1,"Сбросить и выйти")
        assertNull(bot.state.attendanceDraft(1,-1))
        assertTrue(bot.service.trainings(Access(-1,1)).items.single().players.all { it.playing && it.paid==0L })
    }

    @Test fun `back from history returns to training and original training list page`() {
        setup()
        repeat(20) { bot.service.execute(Access(-1,1,true),"new:$it",SettlementCommand.CreateTraining("t$it","Тренировка $it","2026-09-09","19:00")) }
        open(1);click(1,"Управление тренировками");click(1,"Дальше");click(1,"Дальше")
        val list=latest(1)
        click(1,"Тренировка")
        val title=latest(1).text!!.lineSequence().first { it.startsWith("Тренировка") }
        click(1,"История изменений");click(1,"Назад")
        assertTrue(latest(1).text!!.contains(title))
        click(1,"Назад")
        assertEquals(list.text,latest(1).text)
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="3 / 3" })
    }

    @Test fun `selection order survives attendance changes and names have profile verification`() {
        setup();create()
        for(id in 5L..16L) { bot.service.remember(Account(id,"Тёзка"));bot.service.rememberMembership(-1,id,true) }
        click(1,"Игроки");click(1,"Участники группы")
        val original=bot.state.form(1,1)!!.order
        click(1,"Тёзка · #1")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.url=="tg://user?id=5" })
        click(1,"Назад")
        click(1,"Тёзка · #1");click(1,"Выбрать этого участника")
        val auth=Access(-1,1,true)
        bot.service.execute(auth,"old-create",SettlementCommand.CreateTraining("old","Ранее","2026-09-08","19:00"))
        bot.service.execute(auth,"old-join",SettlementCommand.AddPlayers("old",1,listOf(16)))
        bot.service.execute(auth,"old-time",SettlementCommand.ChangeAttendance("old",16,AttendanceChange.SET_MINUTES,60))
        bot.service.execute(auth,"old-pay",SettlementCommand.ChangeAttendance("old",16,AttendanceChange.MARK_PAID))
        bot.service.execute(auth,"old-finish",SettlementCommand.FinishTraining("old",bot.service.training(auth,"old").version))
        click(1,"Дальше");click(1,"Назад")
        assertEquals(original,bot.state.form(1,1)!!.order)
        assertEquals(listOf(5L),bot.state.form(1,1)!!.selectedUsers)
        click(1,"Отмена");click(1,"Сбросить и выйти");click(1,"Участники группы")
        assertEquals(16L,bot.state.form(1,1)!!.order!!.first())
    }

    @Test fun `render batches button writes and roster reads use prepared attendance`() {
        val queries=mutableListOf<String>();setup { queries+=it };create()
        val auth=Access(-1,1,true);val t=bot.service.trainings(auth).items.single()
        val db=bot.service.database
        val before=db.writeTransactions.get()
        val rendered=bot.screens.render(ScreenAction("training",-1,t.id),auth,"batch-measure",1)
        val batch=db.writeTransactions.get()-before
        val actions=rendered.tokens.map { bot.state.button(it)!!.action }
        val previous=db.writeTransactions.get()
        actions.forEach { bot.state.button(it,1,"individual-measure") }
        val individual=db.writeTransactions.get()-previous
        assertEquals(1,batch);assertEquals(actions.size.toLong(),individual)
        queries.clear();repeat(3) { bot.service.roster(auth) }
        assertFalse(queries.any { it.contains("training_players",ignoreCase=true) })
        println("Button transactions: individual=$individual, batch=$batch; roster training scans=0")
    }

    @Test fun `accounting controls use approved labels and preserve balances while correcting`() {
        setup();create();join(2,60);pay(2);closeParticipation(2)
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт")
        assertTrue(latest(1).text!!.contains("Учтена"))
        val balances=bot.service.balances(Access(-1,1))
        click(1,"Открыть заново")
        assertEquals(balances,bot.service.balances(Access(-1,1)))
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт")
        assertEquals(balances,bot.service.balances(Access(-1,1)))
        assertTrue(latest(1).text!!.contains("Учтена"))
    }

    @Test fun `admin restores cancelled training from history and updates the same public card`() {
        setup();create();join(2,60);pay(2);closeParticipation(2)
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт")
        val auth=Access(-1,1,true)
        val before=bot.service.trainings(auth).items.single()
        val publicId=publicCard().id
        click(1,"Отменить тренировку");click(1,"Да, отменить тренировку");bot.maintain()
        assertTrue(publicCard().text!!.contains("Отменена"))
        open(2);click(2,"Мои тренировки");click(2,before.title)
        assertFalse(latest(2).keyboard!!.rows.flatten().any { it.text.contains("Восстановить тренировку") })
        open(1);click(1,"Управление тренировками");click(1,before.title)
        val update=click(1,"Восстановить тренировку")
        bot.handle(update);bot.maintain()
        assertTrue(latest(1).text!!.contains("Открыта"))
        assertTrue(latest(1).text!!.contains("Проверь данные"))
        assertEquals(TrainingPhase.OPEN,bot.service.training(auth,before.id).phase)
        assertTrue(bot.service.balances(auth).values.all { it==0L })
        assertEquals(publicId,publicCard().id)
        assertEquals(1,fake.sent.count { it.chat.id == -1L })
        assertTrue(publicCard().text!!.contains("Открыта"))
        click(1,"История изменений")
        assertTrue(latest(1).text!!.contains("Восстановил тренировку; расчёт ещё не учтён"))
        click(1,"Назад");click(1,"Учесть тренировку");click(1,"Подтвердить учёт");bot.maintain()
        assertTrue(publicCard().text!!.contains("Учтена"))
        assertEquals(before.players,bot.service.training(auth,before.id).players)
    }

    @Test fun `saved restore button checks live administrator rights`() {
        setup();create();click(1,"Отменить тренировку");click(1,"Да, отменить тренировку")
        val cancelled=bot.service.trainings(Access(-1,1)).items.single()
        val card=latest(1)
        fake.members[-1L to 1L]=TgMember("member")
        click(1,"Восстановить тренировку",card)
        assertTrue(answers.last().contains("администратор"))
        assertEquals(cancelled,bot.service.training(Access(-1,1),cancelled.id))
    }

    @Test fun `unchanged details exit directly and changed details require an explicit discard`() {
        setup();create();click(1,"Изменить название и время");click(1,"Отмена")
        assertFalse(latest(1).text!!.contains("несохранённые"))
        val before=bot.service.trainings(Access(-1,1)).items.single()
        click(1,"Изменить название и время");message(1,"Другое название");click(1,"Отмена")
        assertTrue(latest(1).text!!.contains("несохранённые"))
        click(1,"Продолжить ввод")
        assertEquals("Другое название",bot.state.form(1,1)!!.title)
        click(1,"Отмена");click(1,"Сбросить и выйти")
        assertEquals(before,bot.service.training(Access(-1,1),before.id))
    }

    @Test fun `completed event plans are released while incomplete recovery and financial history survive`() {
        setup();create()
        val completed=90001L;val pending=90002L
        val plan=EventPlan(1,1,ScreenAction("menu",-1))
        bot.state.plan(completed,plan);bot.state.complete(completed)
        assertTrue(bot.state.completed(completed));assertNull(bot.state.plan(completed))
        bot.state.plan(pending,plan)
        bot.service.database.write { c -> sqlUpdate(c,"UPDATE bot_events SET plan_json=? WHERE update_id=?",bot.state.json.encodeToString(EventPlan.serializer(),plan),completed) }
        val history=bot.service.history(Access(-1,1)).items
        bot.state.cleanup()
        assertTrue(bot.state.completed(completed));assertNull(bot.state.plan(completed))
        assertEquals(plan,bot.state.plan(pending));assertEquals(pending,bot.state.offset())
        assertEquals(history,bot.service.history(Access(-1,1)).items)
        val count=bot.service.history(Access(-1,1)).total
        bot.handle(TgUpdate(completed,TgMessage(9,TgChat(1,"private"),TgUser(1,firstName="User 1"),"/start")))
        assertEquals(count,bot.service.history(Access(-1,1)).total)
    }

    @Test fun `balance filter toggles do not accumulate an unbounded return chain`() {
        setup();open(2);click(2,"Мои расчёты");click(2,"Баланс группы")
        val sizes=mutableListOf<Int>()
        repeat(25) {
            click(2,"Нулевой баланс");click(2,"Баланс группы")
            val button=latest(2).keyboard!!.rows.flatten().first { it.text.contains("Нулевой баланс") }
            val action=bot.state.button(button.callbackData!!.removePrefix("n:"))!!.action
            sizes+=bot.state.json.encodeToString(ScreenAction.serializer(),action).length
        }
        assertEquals(1,sizes.distinct().size)
        click(2,"Назад");assertTrue(latest(2).text!!.contains("Мой баланс"))
    }

}
