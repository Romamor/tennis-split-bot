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
    private fun setup() {
        bot = SelfServiceBot(api, Database(dir.resolve("new.sqlite")), fake.bot, clock)
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
        assertFalse(card.keyboard!!.rows.flatten().any { it.text == "Завершить" })
        click(2, "Играл", card)
        var personal = ephemeralMessages.getValue(-1L to 2L)
        assertTrue(personal.text!!.contains("Играл 1 ч"))
        assertFalse(personal.keyboard!!.rows.flatten().any { it.text == "Завершить" })
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
        click(2, "Играл", publicCard())
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.contains("Первая группа"))
        confirm(2)
        assertEquals(1, bot.service.trainings(Access(-1, 2), mine = true).total)
        assertEquals(0, bot.service.trainings(Access(-2, 2), mine = true).total)
        val wrongChat = publicCard().copy(chat = TgChat(-2, "supergroup", "Вторая группа"))
        click(3, "Играл", wrongChat)
        assertTrue(answers.last().contains("другой группе"))
        assertEquals(1, bot.service.trainings(Access(-1, 1)).items.single().players.size)
    }

    @Test fun `rights are checked live after revocation including saved admin buttons`() {
        setup(); create()
        val adminCard = latest(1)
        fake.members[-1L to 1L] = TgMember("member")
        click(1, "Завершить", adminCard)
        assertTrue(answers.last().contains("администратору"))
        val training = bot.service.trainings(Access(-1, 1)).items.single()
        val forged = bot.state.button(ScreenAction("finish", -1, training.id, version = training.version), 2, "personal:2:2")
        bot.handle(TgUpdate(updateId++, callback = TgCallback("forge", TgUser(2), TgMessage(2, TgChat(2, "private")), "n:$forged")))
        assertEquals(TrainingPhase.OPEN, bot.service.training(Access(-1, 2), training.id).phase)
        fake.members[-1L to 2L] = TgMember("left")
        click(2, "Играл", publicCard())
        assertTrue(answers.last().contains("участникам"))
    }

    @Test fun `one users personal panel cannot be operated by another`() {
        setup(); create(); click(2, "Играл", publicCard())
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
        click(2, "Записать перевод"); click(2, "User 1"); click(2, "Я перевёл")
        message(2, "450")
        assertEquals(emptyMap(), bot.service.balances(Access(-1, 2)))
        val recorded = click(2, "Деньги переданы")
        bot.handle(recorded)
        assertEquals(mapOf(1L to -450L, 2L to 450L), bot.service.balances(Access(-1, 2)))
        assertNull(bot.state.form(2, 2))
        click(2, "Уточнить")
        assertEquals(mapOf(1L to 0L, 2L to 0L), bot.service.balances(Access(-1, 2)))
        click(2, "Деньги получены")
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
        click(2, "Играл", card)
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
        setup(); create(); click(2,"Играл",publicCard());confirm(2)
        open(2); click(2,"Мои тренировки"); click(2,"09.09.2026"); click(2,"Моё участие"); click(2,"Другая сумма")
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
        val update = message(1,"Вечерний теннис")
        val delivered = latest(1)
        val count = fake.sent.size
        bot.state.database.write { c -> sqlUpdate(c,"UPDATE bot_events SET completed=0 WHERE update_id=?",update.id) }
        bot.handle(update)
        assertEquals(count,fake.sent.size)
        assertEquals(delivered.id,latest(1).id)
    }

    @Test fun `unknown new reply is not resent on replay but new user input gets a fresh reply`() {
        setup(); open(1); click(1,"Создать тренировку")
        fake.acceptThenFail = { chat,_ -> chat==1L }
        val update = message(1,"Вечерний теннис")
        assertEquals("UNKNOWN",bot.state.delivery("personal:1:1")!!.status)
        val count = fake.sent.size
        bot.state.database.write { c -> sqlUpdate(c,"UPDATE bot_events SET completed=0 WHERE update_id=?",update.id) }
        bot.handle(update)
        assertEquals(count,fake.sent.size)
        message(1,"10.09.2026")
        assertEquals(count+1,fake.sent.size)
        assertEquals("SENT",bot.state.delivery("personal:1:1")!!.status)
    }

    @Test fun `updating the shared card preserves personal time payment and leave controls`() {
        setup(); create()
        click(2,"Играл",publicCard())
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
        setup();create();click(2,"Играл",publicCard());bot.maintain()
        val before=bot.service.trainings(Access(-1,1)).items.single()
        val messageId=publicCard().id
        val sentCount=fake.sent.size
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        bot.maintain()
        assertEquals(before,bot.service.trainings(Access(-1,1)).items.single())
        assertEquals(messageId,publicCard().id)
        assertEquals(sentCount,fake.sent.size)
        assertTrue(publicCard().keyboard!!.rows.flatten().any { it.text=="Играл" })
    }

    @Test fun `group panel omits custom amount and only the private panel offers it`() {
        setup();create();click(2,"Играл",publicCard())
        val panel=ephemeralMessages.getValue(-1L to 2L)
        assertTrue(panel.text!!.contains("Играл 1 ч"))
        assertFalse(panel.keyboard!!.rows.flatten().any { it.text=="Другая сумма" })
        confirm(2)
        open(2);click(2,"Мои тренировки");click(2,"09.09.2026");click(2,"Моё участие")
        assertTrue(latest(2).keyboard!!.rows.flatten().any { it.text=="Другая сумма" })
    }

    @Test fun `reopening and closing personal panels does not remove another users panel or public card`() {
        setup();create();click(2,"Играл",publicCard());click(3,"Играл",publicCard())
        val old=ephemeralMessages.getValue(-1L to 2L)
        val other=ephemeralMessages.getValue(-1L to 3L)
        click(2,"Играл",publicCard())
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
        setup();create();click(2,"Играл",publicCard())
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
        setup();create();click(2,"Играл",publicCard())
        val expired=ephemeralMessages.remove(-1L to 2L)!!
        click(2,"Играл",publicCard())
        val fresh=ephemeralMessages.getValue(-1L to 2L)
        assertNotEquals(expired.ephemeralId,fresh.ephemeralId)
        assertEquals(fresh.ephemeralId,bot.state.currentEphemeral(2,-1))
        assertTrue(bot.service.trainings(Access(-1,2)).items.single().players.isEmpty())
        assertTrue(bot.state.attendanceDraft(2,-1)!!.value.playing)
    }

    @Test fun `only confirmation records one change and unchanged confirmation records nothing`() {
        setup();create();click(2,"Играл",publicCard())
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
        click(2,"Играл",publicCard());confirm(2)
        assertEquals(saved,bot.service.trainings(Access(-1,2)).items.single())
        assertEquals(2,bot.service.history(Access(-1,2)).total)
    }

    @Test fun `unfinished draft resumes after restart and a different training does not replace it`() {
        setup();create();click(2,"Играл",publicCard())
        click(2,"Платил",ephemeralMessages.getValue(-1L to 2L))
        click(2,"+0,5 ч",ephemeralMessages.getValue(-1L to 2L))
        val draft=bot.state.attendanceDraft(2,-1)!!
        val panelId=ephemeralMessages.getValue(-1L to 2L).ephemeralId
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock)
        click(2,"Играл",publicCard())
        assertEquals(draft,bot.state.attendanceDraft(2,-1))
        assertEquals(panelId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        bot.service.execute(Access(-1,1,true),"second-training",SettlementCommand.CreateTraining("second","Вторая","2026-09-10","20:00"))
        bot.maintain()
        val secondCard=fake.messages.values.single { it.chat.id==-1L && it.text!!.contains("Вторая ·") }
        click(2,"Играл",secondCard)
        assertEquals(draft,bot.state.attendanceDraft(2,-1))
        assertEquals(panelId,ephemeralMessages.getValue(-1L to 2L).ephemeralId)
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.contains("Сначала заверши"))
        assertTrue(bot.service.training(Access(-1,2),"second").players.isEmpty())
    }

    @Test fun `confirmation cannot overwrite an administrator change and replay cannot erase a new draft`() {
        setup();create();click(2,"Играл",publicCard());confirm(2)
        click(2,"Играл",publicCard());click(2,"Платил",ephemeralMessages.getValue(-1L to 2L))
        val draft=bot.state.attendanceDraft(2,-1)!!
        bot.service.execute(Access(-1,1,true),"admin-correction",SettlementCommand.ChangeAttendance(draft.training,2,AttendanceChange.SET_PAID,400))
        click(2,"Всё правильно",ephemeralMessages.getValue(-1L to 2L))
        assertTrue(answers.last().contains("уже изменили"))
        assertEquals(draft,bot.state.attendanceDraft(2,-1))
        assertEquals(400,bot.service.training(Access(-1,2),draft.training).players.single().paid)
        click(2,"Играл",publicCard());click(2,"Загрузить сохранённые",ephemeralMessages.getValue(-1L to 2L))
        val confirmation=click(2,"Всё правильно",ephemeralMessages.getValue(-1L to 2L))
        val count=bot.service.history(Access(-1,2)).total
        click(2,"Играл",publicCard());click(2,"+50",ephemeralMessages.getValue(-1L to 2L))
        val newDraft=bot.state.attendanceDraft(2,-1)
        bot.state.database.write { c -> sqlUpdate(c,"UPDATE bot_events SET completed=0 WHERE update_id=?",confirmation.id) }
        bot.handle(confirmation)
        assertEquals(newDraft,bot.state.attendanceDraft(2,-1))
        assertEquals(count,bot.service.history(Access(-1,2)).total)
    }
}
