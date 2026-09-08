package ru.movereon.tennis.telegram

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class TelegramBotTest {
    @TempDir lateinit var directory: Path
    private lateinit var api: FakeTelegramApi
    private lateinit var database: SqliteAccountingStore
    private lateinit var bot: TelegramBot
    private lateinit var service: GroupService
    private val clock=Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC)
    private val chat=-100123L
    private val group="tg:$chat"
    private var updateId=1L
    private var inputMessageId=10000L
    private fun user(id:Long) = TgUser(id,firstName="Пользователь $id")
    private fun member(id:Long=1) = VerifiedGroupMember(group,id,id==1L)
    private fun message(text:String,userId:Long=1,reply:TgMessage?=null,chatId:Long=userId):TgUpdate = TgUpdate(updateId++,
        TgMessage(inputMessageId++,TgChat(chatId,if(chatId < 0) "supergroup" else "private","Теннис"),user(userId),text,reply))
    private fun panel(userId:Long=1):TgMessage = api.messages[userId to requireNotNull(bot.state.session(userId).panelId)]!!
    private fun text(userId:Long=1) = panel(userId).text.orEmpty()
    private fun button(label:String,userId:Long=1):TgButton = panel(userId).keyboard!!.rows.flatten().firstOrNull { it.text==label }
        ?: error("Button '$label' not found in ${panel(userId)}")
    private fun clickData(data:String,userId:Long=1) {
        bot.handle(TgUpdate(updateId++,callback=TgCallback("callback-$updateId",user(userId),panel(userId),data)))
    }
    private fun click(label:String,userId:Long=1):String {
        val data=button(label,userId).callbackData!!
        clickData(data,userId)
        return data
    }
    private fun clickStarts(prefix:String,userId:Long=1) = click(panel(userId).keyboard!!.rows.flatten().first { it.text.startsWith(prefix) }.text,userId)
    private fun reply(value:String,userId:Long=1) {
        val prompt=bot.state.session(userId).input!!.promptId
        bot.handle(message(value,userId,api.messages[userId to prompt]))
    }
    private fun open(userId:Long=1,action:BotAction=BotAction("menu")) {
        val link=bot.state.link(group,action)
        bot.handle(message("/start $link",userId))
    }
    private fun seed() {
        listOf("a" to "Андрей","b" to "Борис","s" to "Саша").forEach { (id,name) ->
            service.execute(member(),"seed-$id",WorkflowCommand.AddParticipant(id,name))
        }
        service.execute(member(),"link-a",WorkflowCommand.LinkSelf("a",1))
        service.execute(member(2),"link-b",WorkflowCommand.LinkSelf("b",1))
    }
    private fun ready(id:String="training",cost:Long=100) {
        service.execute(member(),"create-$id",WorkflowCommand.CreateDraft(id,"2026-09-08"))
        service.execute(member(),"save-$id",WorkflowCommand.SaveDraft(id,1,DraftContent("2026-09-08",
            listOf(PlayerInput("a",60),PlayerInput("b",60)),listOf(PaymentInput("a",cost)))))
    }
    private fun recordViaUi(userId:Long=1):String {
        open(userId)
        click("Отметить перевод",userId)
        if(userId != 1L) { click("Отправитель",userId); click("Андрей",userId) }
        click("Получатель",userId); click("Борис",userId)
        click("Сумма",userId); reply("100",userId)
        click("Проверить перевод",userId)
        return click("Перевод состоялся — записать",userId)
    }

    @BeforeEach fun setup() {
        api=FakeTelegramApi()
        api.members[chat to 900] = TgMember("administrator")
        api.members[chat to 1] = TgMember("administrator")
        api.members[chat to 2] = TgMember("member")
        api.members[chat to 3] = TgMember("member")
        database=SqliteAccountingStore(directory.resolve("bot.sqlite"),clock)
        bot=TelegramBot(api,database,api.bot,clock)
        service=GroupService(database,clock)
        bot.handle(message("/setup",chatId=chat))
    }

    @Test fun `setup posts one menu and ignores ordinary group conversation`() {
        val count=api.sent.size
        repeat(3) { bot.handle(message("/setup",chatId=chat)) }
        bot.handle(message("Кто сегодня играет?",chatId=chat))
        bot.handle(message("/setup@another_bot",chatId=chat))
        assertEquals(count,api.sent.size)
        assertEquals(1,service.audit(member()).size)
        assertTrue(api.messages.values.single { it.chat.id==chat }.keyboard!!.rows.flatten().all { it.url!!.startsWith("https://t.me/tennis_test_bot?start=") })
    }

    @Test fun `manual participant can be added and linked through buttons and a reply`() {
        open()
        click("Участники"); click("Добавить по имени"); reply("Андрей")
        assertTrue(text().contains("Telegram пока не привязан"))
        click("Это я"); click("Подтвердить")
        val profile=service.participants(member()).single().participant
        assertEquals("Андрей",profile.name)
        assertEquals(1L,profile.telegramUserId)
        assertTrue(database.history(group).isEmpty())
        assertEquals(1,api.sent.count { it.chat.id==chat })
    }

    @Test fun `full training dialog adds a plus-one records payments and edits one group card`() {
        seed(); open()
        click("Тренировки"); click("Новая тренировка"); click("Игроки")
        listOf("Андрей","Борис","Саша").forEach { name -> click("Добавить игрока"); click(name) }
        clickStarts("Саша"); click("Добавить +1"); click("К игрокам"); click("К тренировке")
        click("Кто оплатил"); clickStarts("Андрей"); reply("350"); clickStarts("Борис"); reply("400")
        click("К тренировке"); click("Посмотреть расчёт")
        assertTrue(text().contains("Саша: доля 374 ₽"))
        val postToken=click("Учесть тренировку")
        assertEquals(mapOf(ParticipantId("a") to 162L,ParticipantId("b") to 212L,ParticipantId("s") to -374L),database.balances(group))
        val card=api.messages.values.single { it.chat.id==chat && it.text!!.contains("750 ₽") }
        clickData(postToken)
        assertEquals(1,database.history(group).size)
        click("Кто оплатил"); clickStarts("Андрей"); reply("450")
        assertEquals(card,api.messages[chat to card.id])
        click("К тренировке"); click("Посмотреть расчёт")
        assertTrue(text().contains("+162 ₽ → +237 ₽"))
        click("Сохранить изменения")
        assertEquals(2,api.sent.count { it.chat.id==chat })
        assertTrue(api.messages[chat to card.id]!!.text!!.contains("850 ₽"))
        assertEquals(2,database.history(group).size)
    }

    @Test fun `the same create button cannot create a second draft`() {
        seed(); open(); click("Тренировки")
        val token=click("Новая тренировка")
        clickData(token)
        assertEquals(1,service.drafts(member()).size)
    }

    @Test fun `outsiders cannot use forwarded links or another users callbacks`() {
        seed(); open()
        val token=button("Балансы").callbackData!!
        open(9)
        assertTrue(text(9).contains("Доступ к группе не подтверждён"))
        assertFalse(text(9).contains("Андрей"))
        open(2)
        clickData(token,2)
        assertTrue(text(2).contains("Доступ к группе не подтверждён"))
        assertTrue(database.history(group).isEmpty())
    }

    @Test fun `membership is checked again before every action`() {
        seed(); open()
        val token=button("Балансы").callbackData!!
        api.members[chat to 1] = TgMember("left")
        clickData(token)
        assertTrue(text().contains("Доступ к группе не подтверждён"))
        assertTrue(api.membershipCalls.count { it == chat to 1L } >= 2)
    }

    @Test fun `restricted membership requires an explicit is member flag`() {
        api.members[chat to 2] = TgMember("restricted",true)
        open(2)
        assertTrue(text(2).contains("Теннис"))
        api.members[chat to 2] = TgMember("restricted",false)
        open(2)
        assertTrue(text(2).contains("Доступ к группе не подтверждён"))
    }

    @Test fun `Telegram membership failure does not consume update or alter balances`() {
        seed(); open()
        val action=button("Балансы").callbackData!!
        val update=TgUpdate(updateId++,callback=TgCallback("membership-failure",user(1),panel(),action))
        api.memberFailure=true
        assertFailsWith<TelegramFailure> { bot.handle(update) }
        assertFalse(bot.state.completed(update.id))
        assertTrue(database.history(group).isEmpty())
        api.memberFailure=false
        bot.handle(update)
        assertTrue(bot.state.completed(update.id))
    }

    @Test fun `pending text input survives restart and binds to its exact prompt`() {
        seed(); ready(); open(action=BotAction("payments",entity="training"))
        clickStarts("Андрей")
        val oldPrompt=bot.state.session(1).input!!.promptId
        open(action=BotAction("payments",entity="training")); clickStarts("Борис")
        bot.handle(message("999",reply=api.messages[1L to oldPrompt]))
        assertEquals(listOf(PaymentInput("a",100)),service.draft(member(),"training").content.payments)
        bot=TelegramBot(api,SqliteAccountingStore(directory.resolve("bot.sqlite"),clock),api.bot,clock)
        reply("400")
        assertEquals(400L,service.draft(member(),"training").content.payments.single { it.participantId=="b" }.amount)
    }

    @Test fun `fractional amount is rejected and a corrected response is accepted`() {
        seed(); ready(); open(action=BotAction("payments",entity="training")); clickStarts("Андрей")
        reply("1.50")
        assertNotNull(bot.state.session(1).input)
        assertEquals(100L,service.draft(member(),"training").content.payments.single().amount)
        reply("200")
        assertEquals(200L,service.draft(member(),"training").content.payments.single().amount)
    }

    @Test fun `unavailable private notification never falls back to the group`() {
        seed(); api.failChat=2
        val token=recordViaUi()
        assertTrue(text().contains("не доставлено"))
        assertEquals(1,api.sent.count { it.chat.id==chat })
        clickData(token)
        assertEquals(1,database.history(group).size)
        assertEquals(100L,database.balances(group)[ParticipantId("a")])
    }

    @Test fun `recipient can clarify and restore a transfer from a private notification`() {
        seed(); recordViaUi()
        val source=database.history(group).single().command.entityId
        open(2,BotAction("transfer",entity=source))
        click("Уточнить перевод",2); click("Пока не учитывать",2)
        assertTrue(database.balances(group).values.all { it==0L })
        open(1,BotAction("transfer",entity=source))
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text=="Всё верно" })
        click("Всё верно",2)
        assertEquals(100L,database.balances(group)[ParticipantId("a")])
        assertEquals(1,api.sent.count { it.chat.id==chat })
    }

    @Test fun `similar transfer requires the explicit additional payment button`() {
        seed(); recordViaUi()
        recordViaUi(2)
        assertTrue(text(2).contains("Похожий перевод"))
        assertEquals(1,database.history(group).size)
        click("Это ещё один перевод",2)
        assertEquals(2,database.history(group).size)
        assertEquals(200L,database.balances(group)[ParticipantId("a")])
    }

    @Test fun `unknown group send is paused and can be adopted with a reply`() {
        seed(); ready(); open(action=BotAction("preview",entity="training"))
        api.acceptThenFail={ target,text -> target==chat && text.startsWith("🏓 2026") }
        click("Учесть тренировку")
        val card=api.messages.values.single { it.chat.id==chat && it.text!!.startsWith("🏓 2026") }
        assertEquals("UNKNOWN",bot.state.delivery("training:$group:training")?.status)
        bot=TelegramBot(api,database,api.bot,clock)
        bot.delivery.flush()
        assertEquals(2,api.sent.count { it.chat.id==chat })
        bot.handle(message("/restore",reply=card,chatId=chat))
        assertEquals("SENT",bot.state.delivery("training:$group:training")?.status)
        assertEquals(card.id,bot.state.delivery("training:$group:training")?.messageId)
        assertEquals(2,api.sent.count { it.chat.id==chat })
    }

    @Test fun `explicit recovery after deletion does not duplicate on a double click`() {
        seed(); ready(); open(action=BotAction("preview",entity="training")); click("Учесть тренировку")
        val card=bot.state.delivery("training:$group:training")!!
        api.messages.remove(chat to card.messageId!!)
        service.execute(member(),"edit",WorkflowCommand.SaveDraft("training",3,DraftContent("2026-09-08",listOf(PlayerInput("a",60),PlayerInput("b",60)),listOf(PaymentInput("a",200)))))
        service.execute(member(),"repost",WorkflowCommand.PostDraft("training",4))
        bot.delivery.flush()
        assertEquals("FAILED",bot.state.delivery(card.key)?.status)
        open(); click("Восстановить карточки"); click("Карточка тренировки")
        val recovery=click("В чате нет карточки — отправить")
        assertEquals(3,api.sent.count { it.chat.id==chat })
        clickData(recovery)
        assertEquals(3,api.sent.count { it.chat.id==chat })
    }

    @Test fun `group card uses published content while a draft is being edited`() {
        seed(); ready()
        service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        service.execute(member(),"working-edit",WorkflowCommand.SaveDraft("training",3,DraftContent("2026-09-09",listOf(PlayerInput("a",120)),listOf(PaymentInput("a",999)))))
        bot.delivery.flush()
        val card=api.messages.values.single { it.chat.id==chat && it.text!!.startsWith("🏓 2026") }
        assertTrue(card.text!!.contains("2026-09-08 · 100 ₽"))
        assertFalse(card.text.contains("999"))
    }

    @Test fun `bot removal prevents private disclosure`() {
        seed(); open()
        api.members[chat to api.bot.id]=TgMember("member")
        click("Балансы")
        assertTrue(text().contains("Доступ к группе не подтверждён"))
    }

    @Test fun `buttons fit Telegram callback limits and remain bound to their user`() {
        seed(); open()
        panel().keyboard!!.rows.flatten().forEach {
            assertTrue(it.callbackData!!.toByteArray().size <= 64)
            assertEquals(1L,bot.state.action(it.callbackData)?.userId)
        }
    }

    @Test fun `a database cannot be rebound to a different bot`() {
        assertFailsWith<IllegalArgumentException> { TelegramBot(api,database,TgUser(901,true,"Other","other_bot"),clock) }
    }

    @Test fun `permanent membership rejection denies access without blocking later updates`() {
        seed(); open()
        val token=button("Балансы").callbackData!!
        api.memberRejected=true
        val update=TgUpdate(updateId++,callback=TgCallback("rejected",user(1),panel(),token))
        bot.handle(update)
        assertTrue(bot.state.completed(update.id))
        assertTrue(text().contains("Доступ к группе не подтверждён"))
        assertTrue(database.history(group).isEmpty())
    }

    @Test fun `setting common time before choosing players gives a useful error`() {
        seed(); open(); click("Тренировки"); click("Новая тренировка"); click("Время всем")
        assertTrue(text().contains("Сначала добавь игроков"))
        assertEquals(null,bot.state.session(1).input)
    }

    @Test fun `unconnected historical groups cannot starve connected notification events`() {
        seed()
        repeat(101) { index -> database.execute("local-fixture","cmd-$index",Actor("local",ParticipantId("a")),
            Command.RecordTransfer("pay-$index",ParticipantId("a"),ParticipantId("b"),1)) }
        ready(); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        bot.delivery.flush()
        assertEquals("SENT",bot.state.delivery("training:$group:training")?.status)
        assertTrue(database.pendingEvents(groupId=group).isEmpty())
    }

    @Test fun `common time is set with a button while custom time remains available`() {
        seed(); ready(); open(action=BotAction("draft",entity="training"))
        click("Время всем"); click("1,5 часа")
        assertTrue(service.draft(member(),"training").content.players.all { it.minutes==90L })
        click("Время всем"); click("Другое время"); reply("75")
        assertTrue(service.draft(member(),"training").content.players.all { it.minutes==75L })
    }

    @Test fun `same-name participants have distinguishable labels without exposing record IDs`() {
        seed()
        service.execute(member(),"second-sasha",WorkflowCommand.AddParticipant("secret-record-id","Саша"))
        open(action=BotAction("participants"))
        val labels=panel().keyboard!!.rows.flatten().map { it.text }
        assertTrue("Саша (1)" in labels && "Саша (2)" in labels)
        assertFalse(labels.any { it.contains("secret-record-id") })
    }
}
