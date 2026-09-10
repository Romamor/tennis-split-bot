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
    private var clock = object : Clock() {
        var now = Instant.parse("2026-09-09T16:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
        override fun instant() = now
    }
    private val api = object : TelegramApi by fake {
        override fun ephemeral(chatId: Long, userId: Long, callbackId: String, text: String, keyboard: TgKeyboard): TgMessage =
            TgMessage(chat = TgChat(chatId, "supergroup"), from = fake.bot, text = text, keyboard = keyboard,
                receiver = TgUser(userId, firstName = "User $userId"), ephemeralId = nextEphemeral++).also { ephemeralMessages[chatId to userId] = it }
        override fun editEphemeral(chatId: Long, userId: Long, ephemeralId: Long, text: String, keyboard: TgKeyboard) {
            val old = ephemeralMessages[chatId to userId] ?: throw TelegramFailure(FailureKind.MESSAGE_MISSING,400)
            assertEquals(old.ephemeralId, ephemeralId)
            ephemeralMessages[chatId to userId] = old.copy(text = text, keyboard = keyboard)
        }
        override fun answer(callbackId: String, text: String?, alert: Boolean) { if (text != null) answers += text }
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
        val button = requireNotNull(message.keyboard).rows.flatten().first { it.text.contains(label) }
        val update = TgUpdate(updateId++, callback = TgCallback("cb:$updateId", TgUser(user, firstName = "User $user"), message, button.callbackData))
        bot.handle(update)
        return update
    }
    private fun open(user: Long, group: String = "Первая") { message(user, "/start"); click(user, group) }
    private fun create() {
        open(1)
        click(1, "Создать тренировку")
        click(1, "Оставить")
        click(1, "09.09.2026")
        click(1, "19:00")
        click(1, "Опубликовать")
        bot.maintain()
    }
    private fun publicCard() = fake.messages.values.single { it.chat.id == -1L }
    private fun confirm(user:Long) {
        click(user,"Всё правильно",ephemeralMessages.getValue(-1L to user))
        bot.maintain()
    }

    @Test fun `admin creates in private and group members use personal controls without public spam`() {
        setup(); create()
        assertEquals(1, bot.service.trainings(Access(-1, 1)).total)
        val card = publicCard()
        assertTrue(card.text!!.contains("Первая группа"))
        assertFalse(card.keyboard!!.rows.flatten().any { it.text.contains("Учесть тренировку") || it.text.contains("Применить правки") })
        click(2, "Участие", card)
        var personal = ephemeralMessages.getValue(-1L to 2L)
        assertTrue(personal.text!!.contains("Играл 1 ч"))
        assertFalse(personal.keyboard!!.rows.flatten().any { it.text.contains("Учесть тренировку") || it.text.contains("Применить правки") })
        click(2, "Платил", personal)
        personal = ephemeralMessages.getValue(-1L to 2L)
        val firstDelta = click(2, "+50", personal)
        bot.handle(firstDelta)
        click(2, "+50", ephemeralMessages.getValue(-1L to 2L))
        click(2, "+0,5 ч", ephemeralMessages.getValue(-1L to 2L))
        bot.maintain()
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty())
        assertEquals(1,bot.service.history(Access(-1,2)).total)
        confirm(2)
        val row = bot.service.trainings(Access(-1, 2)).items.single().players.single()
        assertEquals(400, row.paid)
        assertEquals(90, row.minutes)
        assertEquals(1, fake.sent.count { it.chat.id == -1L })
        assertTrue(publicCard().text!!.contains("1,5 ч"))
        assertTrue(publicCard().text!!.contains("400 ₽"))
        assertFalse(publicCard().text!!.contains("мин"))
    }

    @Test fun `private group choice and public callbacks never inherit another group`() {
        setup(); create(); open(2, "Вторая")
        click(2, "Участие", publicCard())
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.contains("Первая группа"))
        confirm(2)
        assertEquals(1, bot.service.trainings(Access(-1, 2), mine = true).total)
        assertEquals(0, bot.service.trainings(Access(-2, 2), mine = true).total)
        val wrongChat = publicCard().copy(chat = TgChat(-2, "supergroup", "Вторая группа"))
        click(3, "Участие", wrongChat)
        assertTrue(answers.last().contains("другой группе"))
        assertEquals(1, bot.service.trainings(Access(-1, 1)).items.single().players.size)
    }

    @Test fun `rights are checked live after revocation including saved admin buttons`() {
        setup(); create()
        val adminCard = latest(1)
        fake.members[-1L to 1L] = TgMember("member")
        click(1, "Учесть тренировку", adminCard)
        assertTrue(answers.last().contains("администратору"))
        val training = bot.service.trainings(Access(-1, 1)).items.single()
        val forged = bot.state.button(ScreenAction("finish", -1, training.id, version = training.version), 2, "personal:2:2")
        bot.handle(TgUpdate(updateId++, callback = TgCallback("forge", TgUser(2), TgMessage(2, TgChat(2, "private")), "n:$forged")))
        assertEquals(TrainingPhase.OPEN, bot.service.training(Access(-1, 2), training.id).phase)
        fake.members[-1L to 2L] = TgMember("left")
        click(2, "Участие", publicCard())
        assertTrue(answers.last().contains("участникам"))
    }

    @Test fun `one users personal panel cannot be operated by another`() {
        setup(); create(); click(2, "Участие", publicCard())
        click(3, "Платил", ephemeralMessages.getValue(-1L to 2L))
        assertTrue(answers.last().contains("другого участника"))
        assertTrue(bot.service.trainings(Access(-1,1)).items.single().players.isEmpty())
        assertEquals(0,bot.state.attendanceDraft(2,-1)!!.value.paid)
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
        setup(); create()
        val card = publicCard()
        val token = card.keyboard!!.rows.flatten().first().callbackData!!.removePrefix("n:")
        val permanent = card.keyboard!!.rows.flatten().last().url!!.substringAfter("n_")
        val previousPrivate = latest(1).keyboard!!.rows.flatten().first().callbackData!!.removePrefix("n:")
        message(1, "/start")
        clock.now = clock.now.plusSeconds(15 * 86400)
        bot.maintain()
        assertNotNull(bot.state.button(token))
        assertNotNull(bot.state.button(permanent))
        assertNull(bot.state.button(previousPrivate))
        click(2, "Участие", card)
        confirm(2)
        assertEquals(1, bot.service.trainings(Access(-1, 2)).items.single().players.size)
    }

    @Test fun `27 players and a long history fit in paged messages`() {
        setup(); create()
        val a = Access(-1, 1, true)
        val t = bot.service.trainings(a).items.single()
        for (id in 5L..31L) {
            bot.service.remember(Account(id, "Имя".repeat(30), username = "name".repeat(8)))
            bot.service.rememberMembership(-1, id, true)
            bot.service.execute(a, "join:$id", SettlementCommand.ChangeAttendance(t.id, id, AttendanceChange.JOIN))
        }
        bot.maintain()
        for (kind in listOf("training", "history", "roster")) {
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
        click(1,"09.09.2026"); click(1,"19:00")
        val oldPreview = latest(1)
        click(1,"Изменить название")
        message(1,"Новое название"); click(1,"09.09.2026"); click(1,"19:00")
        click(1,"Опубликовать",oldPreview)
        assertEquals(0,bot.service.trainings(Access(-1,1)).total)
        assertTrue(answers.last().contains("Форма изменилась"))
        click(1,"Опубликовать")
        assertEquals("Новое название",bot.service.trainings(Access(-1,1)).items.single().title)
    }

    @Test fun `revoked membership during text input does not crash or apply a payment`() {
        setup(); create(); click(2,"Участие",publicCard());confirm(2)
        open(2); click(2,"Мои тренировки"); click(2,"09.09.2026"); click(2,"Изменить участие"); click(2,"Другая сумма")
        fake.members[-1L to 2L] = TgMember("left")
        message(2,"1000")
        assertEquals(0,bot.service.trainings(Access(-1,1)).items.single().players.single().paid)
        assertTrue(latest(2).text!!.contains("Доступ только участникам"))
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
        assertEquals(titlePrompt,fake.messages[1L to titlePrompt.id],"The old prompt must not be edited above the user input")
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
        setup(); create()
        click(2,"Участие",publicCard())
        bot.maintain()
        val personal = ephemeralMessages.getValue(-1L to 2L)
        val buttons = personal.keyboard!!.rows.flatten()
        assertTrue(buttons.any { it.text=="+0,5 ч" })
        assertTrue(buttons.any { it.text=="Платил · 300 ₽" })
        assertTrue(buttons.any { it.text=="Не играл" })
        buttons.mapNotNull { it.callbackData?.removePrefix("n:") }.forEach { assertNotNull(bot.state.button(it)) }
        click(2,"Платил",personal)
        bot.maintain()
        click(2,"Не играл",ephemeralMessages.getValue(-1L to 2L))
        assertTrue(bot.state.attendanceDraft(2,-1)!!.value.playing)
        click(2,"Не играл — убрать 300 ₽",ephemeralMessages.getValue(-1L to 2L))
        bot.maintain()
        val row = bot.state.attendanceDraft(2,-1)!!.value
        assertFalse(row.playing)
        assertEquals(0,row.paid)
        assertFalse(ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.flatten().any { it.text.contains("₽") })
        confirm(2)
        assertTrue(bot.service.trainings(Access(-1,1)).items.single().players.isEmpty())
        assertEquals(1,bot.service.history(Access(-1,1)).total)
        assertEquals(1,fake.sent.count { it.chat.id==-1L },"No personal panels are posted as ordinary group messages")
    }

    @Test fun `superadmin can appoint and remove a bot admin through the UI in one group only`() {
        setup()
        bot.service.execute(Access(-2,3,true),"other-group-admin",SettlementCommand.SetAdministrator(2,true))
        open(1)
        click(1,"Администраторы бота")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 1") && it.text.contains("Суперадмин") })
        assertFalse(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 2") })
        click(1,"Назначить администратора")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text.contains("User 3") && it.text.contains("Участник") })
        click(1,"User 2")
        click(1,"Назначить администратором")
        assertTrue(bot.service.isAdmin(Access(-1,2)))
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
        open(2)
        assertFalse(latest(2).keyboard!!.rows.flatten().any { it.text=="Создать тренировку" })
    }

    @Test fun `startup refresh updates existing live cards without changing participation or publishing copies`() {
        setup();create();click(2,"Участие",publicCard());bot.maintain()
        val before=bot.service.trainings(Access(-1,1)).items.single()
        val messageId=publicCard().id
        val sentCount=fake.sent.size
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        bot.maintain()
        assertEquals(before,bot.service.trainings(Access(-1,1)).items.single())
        assertEquals(messageId,publicCard().id)
        assertEquals(sentCount,fake.sent.size)
        assertTrue(publicCard().keyboard!!.rows.flatten().any { it.text=="Участие" })
    }

    @Test fun `group panel omits custom amount and only the private panel offers it`() {
        setup();create();click(2,"Участие",publicCard())
        val panel=ephemeralMessages.getValue(-1L to 2L)
        assertTrue(panel.text!!.contains("Играл 1 ч"))
        assertFalse(panel.keyboard!!.rows.flatten().any { it.text=="Другая сумма" })
        confirm(2)
        open(2);click(2,"Мои тренировки");click(2,"09.09.2026");click(2,"Изменить участие")
        assertTrue(latest(2).keyboard!!.rows.flatten().any { it.text=="Другая сумма" })
    }

    @Test fun `reopening and closing personal panels does not remove another users panel or public card`() {
        setup();create();click(2,"Участие",publicCard());click(3,"Участие",publicCard())
        val old=ephemeralMessages.getValue(-1L to 2L)
        val other=ephemeralMessages.getValue(-1L to 3L)
        click(2,"Участие",publicCard())
        val fresh=ephemeralMessages.getValue(-1L to 2L)
        assertEquals(old.ephemeralId,fresh.ephemeralId)
        assertFalse(deletedEphemerals.contains(Triple(-1L,2L,old.ephemeralId!!)))
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty())
        click(2,"Всё правильно",fresh)
        assertNull(bot.state.currentEphemeral(2,-1))
        assertFalse(ephemeralMessages.containsKey(-1L to 2L))
        assertEquals(other,ephemeralMessages[-1L to 3L])
        assertEquals(2,bot.service.trainings(Access(-1,2)).items.single().players.single().userId)
        assertNull(bot.state.attendanceDraft(2,-1))
        assertNotNull(bot.state.attendanceDraft(3,-1))
        assertEquals(1,fake.sent.count { it.chat.id==-1L })
    }

    @Test fun `private navigation uses a direct link and removes the group panel without an explanatory message`() {
        setup();create();click(2,"Участие",publicCard());confirm(2);click(2,"Участие",publicCard())
        click(2,"Карточка тренировки",ephemeralMessages.getValue(-1L to 2L))
        val panel=ephemeralMessages.getValue(-1L to 2L)
        val link=panel.keyboard!!.rows.flatten().single { it.text=="В меню группы" }
        assertNull(link.callbackData)
        assertNotNull(link.url)
        val sent=fake.sent.count { it.chat.id==-1L }
        message(2,"/start ${link.url!!.substringAfter("start=")}")
        assertTrue(latest(2).text!!.contains("Что хочешь сделать"))
        assertFalse(ephemeralMessages.containsKey(-1L to 2L))
        assertEquals(sent,fake.sent.count { it.chat.id==-1L })
        assertFalse(fake.sent.any { it.text?.contains("Это действие доступно в личном") == true })
    }

    @Test fun `expired personal panel is replaced on the next participation click`() {
        setup();create();click(2,"Участие",publicCard())
        val expired=ephemeralMessages.remove(-1L to 2L)!!
        click(2,"Участие",publicCard())
        val fresh=ephemeralMessages.getValue(-1L to 2L)
        assertNotEquals(expired.ephemeralId,fresh.ephemeralId)
        assertEquals(fresh.ephemeralId,bot.state.currentEphemeral(2,-1))
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty())
        assertTrue(bot.state.attendanceDraft(2,-1)!!.value.playing)
    }

    @Test fun `only confirmation records one change and unchanged confirmation records nothing`() {
        setup();create();click(2,"Участие",publicCard())
        val originalCard=publicCard()
        click(2,"Платил",ephemeralMessages.getValue(-1L to 2L))
        repeat(4) { click(2,"+50",ephemeralMessages.getValue(-1L to 2L)) }
        click(2,"+0,5 ч",ephemeralMessages.getValue(-1L to 2L))
        bot.maintain()
        assertEquals(originalCard,publicCard())
        assertEquals(1,bot.service.history(Access(-1,2)).total)
        val confirmation=click(2,"Всё правильно",ephemeralMessages.getValue(-1L to 2L))
        bot.handle(confirmation)
        bot.maintain()
        val saved=bot.service.trainings(Access(-1,2)).items.single()
        assertEquals(500,saved.players.single().paid)
        assertEquals(90,saved.players.single().minutes)
        assertEquals(2,bot.service.history(Access(-1,2)).total)
        assertEquals("SaveAttendance",bot.service.history(Access(-1,2)).items.first().kind)
        click(2,"Участие",publicCard());confirm(2)
        assertEquals(saved,bot.service.trainings(Access(-1,2)).items.single())
        assertEquals(2,bot.service.history(Access(-1,2)).total)
    }

    @Test fun `unfinished draft resumes after restart and a different training does not replace it`() {
        setup();create();click(2,"Участие",publicCard())
        click(2,"Платил",ephemeralMessages.getValue(-1L to 2L))
        click(2,"+0,5 ч",ephemeralMessages.getValue(-1L to 2L))
        val draft=bot.state.attendanceDraft(2,-1)!!
        val panelId=ephemeralMessages.getValue(-1L to 2L).ephemeralId
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        click(2,"Участие",publicCard())
        assertEquals(draft,bot.state.attendanceDraft(2,-1))
        assertEquals(panelId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        bot.service.execute(Access(-1,1,true),"second-training",SettlementCommand.CreateTraining("second","Вторая","2026-09-10","20:00"))
        bot.maintain()
        val secondCard=fake.messages.values.single { it.chat.id==-1L && it.text!!.contains("Вторая ·") }
        click(2,"Участие",secondCard)
        assertEquals(draft,bot.state.attendanceDraft(2,-1))
        assertEquals(panelId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.contains("Сначала заверши"))
        assertTrue(bot.service.training(Access(-1,2),"second").players.isEmpty())
    }

    @Test fun `confirmed player can edit attendance while another player joins through the same shared card`() {
        setup();create();click(2,"Участие",publicCard());confirm(2)
        open(2);click(2,"Мои тренировки");click(2,"09.09.2026")
        assertFalse(latest(2).keyboard!!.rows.flatten().any { it.text=="Играл" })
        click(2,"Изменить участие");click(2,"+0,5 ч");click(2,"Всё правильно")
        click(3,"Участие",publicCard());confirm(3)
        val t=bot.service.trainings(Access(-1,2)).items.single()
        assertEquals(90,t.players.single { it.userId==2L }.minutes)
        assertEquals(60,t.players.single { it.userId==3L }.minutes)
        click(2,"Изменить участие");click(2,"Не играл");click(2,"Всё правильно")
        assertFalse(bot.service.training(Access(-1,2),t.id).players.single { it.userId==2L }.playing)
        assertTrue(latest(2).keyboard!!.rows.flatten().any { it.text=="Играл" })
        click(2,"Играл")
        assertTrue(bot.state.attendanceDraft(2,-1)!!.value.playing)
        assertFalse(bot.service.training(Access(-1,2),t.id).players.single { it.userId==2L }.playing)
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
        setup();create();click(1,"Игроки");click(1,"Участники группы")
        click(1,"User 2");click(1,"User 3")
        click(2,"Участие",publicCard());click(2,"Платил",ephemeralMessages.getValue(-1L to 2L));confirm(2)
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
        click(1,"Player 13");click(1,"+0,5 ч");click(1,"Платил");click(1,"Добавить гостя")
        click(1,"Всё правильно")
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="2 / 2" })
        assertTrue(latest(1).keyboard!!.rows.flatten().any { it.text=="Player 13 · 1,5 ч · +1 · 300 ₽" })
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

    @Test fun `confirmation cannot overwrite an administrator change and replay cannot erase a new draft`() {
        setup();create();click(2,"Участие",publicCard());confirm(2)
        click(2,"Участие",publicCard());click(2,"Платил",ephemeralMessages.getValue(-1L to 2L))
        val draft=bot.state.attendanceDraft(2,-1)!!
        bot.service.execute(Access(-1,1,true),"admin-correction",SettlementCommand.ChangeAttendance(draft.training,2,AttendanceChange.SET_PAID,400))
        click(2,"Всё правильно",ephemeralMessages.getValue(-1L to 2L))
        assertTrue(answers.last().contains("уже изменили"))
        assertEquals(draft,bot.state.attendanceDraft(2,-1))
        assertEquals(400,bot.service.training(Access(-1,2),draft.training).players.single().paid)
        click(2,"Участие",publicCard());click(2,"Загрузить сохранённые",ephemeralMessages.getValue(-1L to 2L))
        val confirmation=click(2,"Всё правильно",ephemeralMessages.getValue(-1L to 2L))
        val count=bot.service.history(Access(-1,2)).total
        click(2,"Участие",publicCard());click(2,"+50",ephemeralMessages.getValue(-1L to 2L))
        val newDraft=bot.state.attendanceDraft(2,-1)
        bot.state.database.write { c -> sqlUpdate(c,"UPDATE bot_events SET completed=0 WHERE update_id=?",confirmation.id) }
        bot.handle(confirmation)
        assertEquals(newDraft,bot.state.attendanceDraft(2,-1))
        assertEquals(count,bot.service.history(Access(-1,2)).total)
    }
    @Test fun `member menu has two primary items and transfer amount edits preserve review`() {
        setup();bot.service.rememberMembership(-2,2,false);open(2)
        assertEquals(listOf("🏓 Мои тренировки","💰 Мои расчёты"),latest(2).keyboard!!.rows.flatten().map { it.text })
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

    @Test fun `back from dirty attendance warns in group and discards only that input`() {
        setup();create();click(2,"Участие",publicCard());click(3,"Участие",publicCard())
        click(2,"Карточка тренировки",ephemeralMessages.getValue(-1L to 2L))
        assertEquals(listOf("Продолжить ввод","Сбросить и выйти"),ephemeralMessages.getValue(-1L to 2L).keyboard!!.rows.flatten().map { it.text })
        click(2,"Продолжить ввод",ephemeralMessages.getValue(-1L to 2L))
        assertTrue(bot.state.attendanceDraft(2,-1)!!.value.playing)
        click(2,"Карточка тренировки",ephemeralMessages.getValue(-1L to 2L))
        click(2,"Сбросить и выйти",ephemeralMessages.getValue(-1L to 2L))
        assertNull(bot.state.attendanceDraft(2,-1));assertNotNull(bot.state.attendanceDraft(3,-1))
        assertEquals(1,bot.service.history(Access(-1,1)).total)
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
        setup();create();click(2,"Участие",publicCard());click(2,"Платил",ephemeralMessages.getValue(-1L to 2L));confirm(2)
        click(1,"Учесть тренировку");click(1,"Подтвердить учёт")
        assertTrue(latest(1).text!!.contains("Учтена"))
        val balances=bot.service.balances(Access(-1,1))
        click(1,"Исправить тренировку")
        assertEquals(balances,bot.service.balances(Access(-1,1)))
        click(1,"Применить правки");click(1,"Подтвердить правки")
        assertEquals(balances,bot.service.balances(Access(-1,1)))
        assertTrue(latest(1).text!!.contains("Учтена"))
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
