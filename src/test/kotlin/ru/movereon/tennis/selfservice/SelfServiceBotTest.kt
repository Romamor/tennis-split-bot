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
                receiver = TgUser(userId, firstName = "User $userId"), ephemeralId = 700 + userId).also { ephemeralMessages[chatId to userId] = it }
        override fun editEphemeral(chatId: Long, userId: Long, ephemeralId: Long, text: String, keyboard: TgKeyboard) {
            val old = ephemeralMessages.getValue(chatId to userId)
            assertEquals(old.ephemeralId, ephemeralId)
            ephemeralMessages[chatId to userId] = old.copy(text = text, keyboard = keyboard)
        }
        override fun answer(callbackId: String, text: String?, alert: Boolean) { if (text != null) answers += text }
        override fun requestUsers(chatId: Long, text: String, requestId: Int) = fake.send(chatId, text, null, false)
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
    private fun message(user: Long, text: String, chat: Long = user) {
        bot.handle(TgUpdate(updateId++, TgMessage(1000 + updateId, TgChat(chat, if (chat < 0) "supergroup" else "private", if (chat == -1L) "Первая группа" else null), TgUser(user, firstName = "User $user"), text)))
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

    @Test fun `admin creates in private and group members use personal controls without public spam`() {
        setup(); create()
        assertEquals(1, bot.service.trainings(Access(-1, 1)).total)
        val card = publicCard()
        assertTrue(card.text!!.contains("Первая группа"))
        assertFalse(card.keyboard!!.rows.flatten().any { it.text == "Завершить" })
        click(2, "Присоединиться", card)
        var personal = ephemeralMessages.getValue(-1L to 2L)
        assertTrue(personal.text!!.contains("Играет 1 ч"))
        assertFalse(personal.keyboard!!.rows.flatten().any { it.text == "Завершить" })
        click(2, "Платил", personal)
        personal = ephemeralMessages.getValue(-1L to 2L)
        val firstDelta = click(2, "+50", personal)
        bot.handle(firstDelta)
        click(2, "+50", ephemeralMessages.getValue(-1L to 2L))
        click(2, "+0,5 ч", ephemeralMessages.getValue(-1L to 2L))
        bot.maintain()
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
        click(2, "Присоединиться", publicCard())
        assertEquals(1, bot.service.trainings(Access(-1, 2), mine = true).total)
        assertEquals(0, bot.service.trainings(Access(-2, 2), mine = true).total)
        assertTrue(ephemeralMessages.getValue(-1L to 2L).text!!.contains("Первая группа"))
        val wrongChat = publicCard().copy(chat = TgChat(-2, "supergroup", "Вторая группа"))
        click(3, "Присоединиться", wrongChat)
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
        click(2, "Присоединиться", publicCard())
        assertTrue(answers.last().contains("участникам"))
    }

    @Test fun `one users personal panel cannot be operated by another`() {
        setup(); create(); click(2, "Присоединиться", publicCard())
        click(3, "Платил", ephemeralMessages.getValue(-1L to 2L))
        assertTrue(answers.last().contains("другого участника"))
        assertEquals(0, bot.service.trainings(Access(-1, 1)).items.single().players.single().paid)
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
        click(2, "Присоединиться", card)
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
        setup(); create(); click(2,"Присоединиться",publicCard())
        open(2); click(2,"Мои тренировки"); click(2,"09.09.2026"); click(2,"Мои время"); click(2,"Другая сумма")
        fake.members[-1L to 2L] = TgMember("left")
        message(2,"1000")
        assertEquals(0,bot.service.trainings(Access(-1,1)).items.single().players.single().paid)
        assertTrue(latest(2).text!!.contains("Доступ только участникам"))
    }
}
