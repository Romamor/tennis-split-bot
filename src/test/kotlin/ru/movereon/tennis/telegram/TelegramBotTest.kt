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
    private fun labels(userId:Long=1) = panel(userId).keyboard!!.rows.flatten().joinToString("\n") { it.text }
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
    private fun editor(id:String="training",userId:Long=1) = bot.state.editors(userId,group).single { it.draftId==id }
    private fun input(id:String="training",userId:Long=1) = editor(id,userId).content
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
        click("Записать перевод",userId); click("Я перевёл",userId)
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
        click("Ещё"); click("Участники"); click("Добавить по имени"); reply("Андрей")
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
        click("Записать тренировку")
        listOf("Андрей","Борис","Саша").forEach { name -> click("⬜ $name") }
        click("Дальше: оплаты · 3"); click("Игроки")
        clickStarts("Саша"); click("Добавить +1"); click("К игрокам"); click("К тренировке")
        click("Кто оплатил"); clickStarts("Андрей"); reply("350"); clickStarts("Борис"); reply("400")
        click("К тренировке"); click("Ещё"); click("Посмотреть расчёт")
        assertTrue(text().contains("Саша: доля 374 ₽"))
        val postToken=click("Записать тренировку")
        assertEquals(mapOf(ParticipantId("a") to 162L,ParticipantId("b") to 212L,ParticipantId("s") to -374L),database.balances(group))
        val card=api.messages.values.single { it.chat.id==chat && it.text!!.contains("750 ₽") }
        clickData(postToken)
        assertEquals(1,database.history(group).size)
        click("Кто оплатил"); clickStarts("Андрей"); reply("450")
        assertEquals(card,api.messages[chat to card.id])
        click("К тренировке"); click("Ещё"); click("Посмотреть расчёт")
        assertTrue(text().contains("+162 ₽ → +237 ₽"))
        click("Сохранить изменения")
        assertEquals(2,api.sent.count { it.chat.id==chat })
        assertTrue(api.messages[chat to card.id]!!.text!!.contains("850 ₽"))
        assertEquals(2,database.history(group).size)
    }

    @Test fun `the same create button cannot create a second draft`() {
        seed(); open()
        val token=click("Записать тренировку")
        clickData(token)
        assertEquals(0,service.drafts(member()).size)
        assertEquals(1,bot.state.editors(1,group).size)
    }

    @Test fun `outsiders cannot use forwarded links or another users callbacks`() {
        seed(); open()
        val token=button("Кто кому должен").callbackData!!
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
        val token=button("Кто кому должен").callbackData!!
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
        val action=button("Кто кому должен").callbackData!!
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
        assertEquals(400L,input().payments.single { it.participantId=="b" }.amount)
    }

    @Test fun `fractional amount is rejected and a corrected response is accepted`() {
        seed(); ready(); open(action=BotAction("payments",entity="training")); clickStarts("Андрей")
        reply("1.50")
        assertNotNull(bot.state.session(1).input)
        assertEquals(100L,service.draft(member(),"training").content.payments.single().amount)
        reply("200")
        assertEquals(200L,input().payments.single().amount)
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
        click("Записать тренировку")
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
        seed(); ready(); open(action=BotAction("preview",entity="training")); click("Записать тренировку")
        val card=bot.state.delivery("training:$group:training")!!
        api.messages.remove(chat to card.messageId!!)
        service.execute(member(),"edit",WorkflowCommand.SaveDraft("training",3,DraftContent("2026-09-08",listOf(PlayerInput("a",60),PlayerInput("b",60)),listOf(PaymentInput("a",200)))))
        service.execute(member(),"repost",WorkflowCommand.PostDraft("training",4))
        bot.delivery.flush()
        assertEquals("FAILED",bot.state.delivery(card.key)?.status)
        open(); click("Ещё"); click("Восстановить сообщение в группе"); click("Карточка тренировки")
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
        click("Кто кому должен")
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
        val token=button("Кто кому должен").callbackData!!
        api.memberRejected=true
        val update=TgUpdate(updateId++,callback=TgCallback("rejected",user(1),panel(),token))
        bot.handle(update)
        assertTrue(bot.state.completed(update.id))
        assertTrue(text().contains("Доступ к группе не подтверждён"))
        assertTrue(database.history(group).isEmpty())
    }

    @Test fun `empty selection guides user to choose players before payments`() {
        seed(); open(); click("Записать тренировку")
        assertTrue(text().contains("Кто играл?"))
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text.startsWith("Дальше: оплаты") })
        assertFalse(text().contains("Страница 1 из 1"))
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

    @Test fun `time choices use half hours for everyone and individual guests`() {
        seed(); ready(); open(action=BotAction("draft",entity="training"))
        click("Игроки"); click("Время всем"); click("1,5 ч")
        assertTrue(input().players.all { it.minutes==90L })
        assertNotNull(button("Андрей · 1,5 ч"))
        assertFalse(text().contains(" мин"))
        clickStarts("Андрей"); click("Добавить +1")
        click("Изменить время +1"); click("0,5 ч")
        val players=input().players
        assertEquals(30L,players.single { it.plusOne }.minutes)
        assertTrue(players.filterNot { it.plusOne }.all { it.minutes==90L })
        click("К игрокам")
        assertNotNull(button("Андрей · 1,5 ч · +1: 0,5 ч"))
        clickStarts("Андрей"); click("Убрать +1"); click("К игрокам")
        assertNotNull(button("Андрей · 1,5 ч"))
        click("К тренировке"); click("Игроки"); click("Время всем")
        click("Больше →"); click("3,5 ч")
        assertTrue(input().players.all { it.minutes==210L })
    }

    @Test fun `new players default to one hour and published card displays hours`() {
        seed(); open(); click("Записать тренировку"); click("⬜ Андрей"); click("Дальше: оплаты · 1"); click("Игроки")
        assertTrue(panel().keyboard!!.rows.flatten().any { it.text=="Андрей · 1 ч" })
        clickStarts("Андрей"); click("Добавить +1")
        val draft=bot.state.editors(1,group).single().view()
        assertTrue(draft.content.players.all { it.minutes==60L })
        assertTrue(text().contains("+1: 1 ч"))
        click("К игрокам"); click("К тренировке"); click("Кто оплатил"); clickStarts("Андрей"); reply("200")
        click("К тренировке"); click("Ещё"); click("Посмотреть расчёт"); click("Записать тренировку")
        val card=api.messages.values.single { it.chat.id==chat && it.text!!.contains("200 ₽") }
        assertTrue(card.text!!.contains("по 1 ч"))
        assertFalse(card.text.contains(" мин"))
        assertEquals("1,25 ч",hoursLabel(75))
    }

    @Test fun `text input moves controls below the reply and retires the old panel`() {
        open(); click("Ещё"); click("Участники")
        val oldPanel=panel()
        click("Добавить по имени")
        val promptId=bot.state.session(1).input!!.promptId
        reply("Андрей")
        assertTrue(panel().id > promptId)
        assertEquals(panel().id,api.sent.last { it.chat.id==1L }.id)
        assertNull(api.messages[1L to oldPanel.id]!!.keyboard)
        assertTrue(text().contains("Андрей"))
        val currentPanel=panel().id
        val sends=api.sent.size
        click("Это я"); click("Подтвердить")
        assertEquals(currentPanel,panel().id)
        assertEquals(sends,api.sent.size)
        assertEquals(1,api.sent.count { it.chat.id==chat })
    }

    @Test fun `menu command opens a fresh panel once including after restart`() {
        seed(); open(); click("Ещё"); click("Участники")
        val old=panel().id
        val command=message("/menu")
        bot.handle(command)
        assertNotEquals(old,panel().id)
        val current=panel().id
        val sends=api.sent.size
        bot=TelegramBot(api,database,api.bot,clock)
        bot.handle(command)
        assertEquals(current,panel().id)
        assertEquals(sends,api.sent.size)
    }

    @Test fun `uncertain new panel does not repeat data changes or erase old controls`() {
        open(); click("Ещё"); click("Участники"); click("Добавить по имени")
        val old=panel()
        val prompt=bot.state.session(1).input!!.promptId
        val response=message("Андрей",reply=api.messages[1L to prompt])
        api.acceptThenFail={ target,_ -> target==1L }
        bot.handle(response)
        val sends=api.sent.size
        bot.handle(response)
        assertEquals(sends,api.sent.size)
        assertNotNull(api.messages[1L to old.id]!!.keyboard)
        assertEquals(1,service.participants(member()).size)
        bot.handle(message("/menu"))
        click("Ещё"); click("Участники")
        assertNotNull(button("Андрей"))
    }

    @Test fun `history groups draft edits and transfer reviews while retaining every audit event`() {
        seed(); ready("same")
        service.execute(member(),"post",WorkflowCommand.PostDraft("same",2))
        service.execute(member(),"pay",WorkflowCommand.RecordTransfer("same","a","b",50,"2026-09-08"))
        service.execute(member(2),"review",WorkflowCommand.ChangeTransfer("same",1,TransferIntent.REVIEW))
        val auditCount=service.audit(member()).size
        open(action=BotAction("history"))
        assertTrue(labels().contains("Перевод · 08.09.2026 · 50 ₽ ❔"))
        assertFalse(text().contains("50 ₽"))
        assertEquals(2,panel().keyboard!!.rows.flatten().count { it.text.matches(Regex("[0-9]+\\..*")) })
        assertFalse(text().contains("Добавление участника"))
        clickStarts("1. Перевод")
        assertTrue(text().contains("Андрей → Борис: 50 ₽"))
        assertTrue(text().contains("Уточняем · пока не учитывается"))
        click("Изменения записи")
        assertEquals(2,panel().keyboard!!.rows.flatten().count { bot.state.action(it.callbackData!!)?.action?.kind=="audit" })
        clickStarts("08.09 15:00 · Уточнение")
        assertTrue(text().contains("До:"))
        assertTrue(text().contains("Учтён"))
        assertTrue(text().contains("Уточняем"))
        click("К изменениям"); click("К истории"); clickStarts("2. Тренировка"); click("Ещё"); click("Изменения записи")
        assertEquals(3,panel().keyboard!!.rows.flatten().count { bot.state.action(it.callbackData!!)?.action?.kind=="audit" })
        assertEquals(auditCount,service.audit(member()).size)
        click("К истории"); click("Все изменения")
        assertTrue(panel().keyboard!!.rows.flatten().any { it.text.contains("Привязка Telegram") })
    }

    @Test fun `history summary keeps published amount while edits are pending and includes cancellation`() {
        seed(); ready(); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        service.execute(member(),"edit",WorkflowCommand.SaveDraft("training",3,DraftContent("2026-09-09",
            listOf(PlayerInput("a",60)),listOf(PaymentInput("a",999)))))
        open(action=BotAction("history"))
        assertTrue(labels().contains("08.09.2026"))
        assertTrue(labels().contains("100 ₽"))
        assertFalse(labels().contains("999 ₽"))
        assertTrue(labels().contains("✏️"))
        service.execute(member(),"cancel",WorkflowCommand.CancelDraft("training",4))
        open(action=BotAction("history"))
        assertTrue(labels().contains("❌"))
        assertEquals(1,panel().keyboard!!.rows.flatten().count { it.text.startsWith("1. Тренировка") })
    }

    @Test fun `history paginates records rather than individual changes`() {
        seed()
        repeat(9) { ready("training-$it",(it+1)*100L) }
        open(action=BotAction("history"))
        assertEquals(8,panel().keyboard!!.rows.flatten().count { bot.state.action(it.callbackData!!)?.action?.kind=="draft" })
        click("Дальше →")
        assertEquals(1,panel().keyboard!!.rows.flatten().count { bot.state.action(it.callbackData!!)?.action?.kind=="draft" })
        assertTrue(labels().contains("100 ₽"))
        assertNotNull(button("← Назад"))
    }

    @Test fun `twenty-seven-player selection supports bulk selection and stays on its page`() {
        repeat(27) { i -> service.execute(member(),"person-$i",WorkflowCommand.AddParticipant("p$i","Игрок %02d".format(i+1))) }
        service.execute(member(),"draft",WorkflowCommand.CreateDraft("many","2026-09-08"))
        open(action=BotAction("add_players",entity="many"))
        val panelId=panel().id
        assertEquals(12,panel().keyboard!!.rows.flatten().count { it.text.startsWith("⬜") })
        assertTrue(panel().keyboard!!.rows.take(6).all { it.size==2 })
        click("Ещё"); click("Выбрать всех")
        assertEquals(27,input("many").players.size)
        click("Дальше →"); click("✅ Игрок 13")
        assertTrue(text().contains("Страница 2 из 3"))
        assertTrue(text().contains("Выбрано: 26"))
        assertNotNull(button("⬜ Игрок 13"))
        assertEquals(panelId,panel().id)
        assertTrue(input("many").players.all { it.minutes==60L })
        click("Дальше →")
        assertTrue(text().contains("Страница 3 из 3"))
        assertEquals(3,panel().keyboard!!.rows.flatten().count { it.text.startsWith("✅") })
        click("Дальше: оплаты · 26"); click("Игроки")
        assertNotNull(button("Игрок 01 · 1 ч"))
    }

    @Test fun `deselecting a player removes their guest but retains payments and other times`() {
        seed(); ready()
        service.execute(member(),"guest",WorkflowCommand.SaveDraft("training",2,DraftContent("2026-09-08",
            listOf(PlayerInput("a",90),PlayerInput("a",30,true),PlayerInput("b",120)),listOf(PaymentInput("a",100)))))
        open(action=BotAction("add_players",entity="training"))
        assertNotNull(button("✅ Андрей +1"))
        click("✅ Андрей +1")
        val d=editor().view()
        assertEquals(listOf(PlayerInput("b",120)),d.content.players)
        assertEquals(listOf(PaymentInput("a",100)),d.content.payments)
        assertFalse(text().contains("гостей +1:"))
    }

    @Test fun `player choices rank posted attendance without counting guests drafts or cancelled games`() {
        seed()
        fun training(id:String,players:List<PlayerInput>,post:Boolean=true,cancel:Boolean=false) {
            service.execute(member(),"new-$id",WorkflowCommand.CreateDraft(id,"2026-09-08"))
            service.execute(member(),"save-$id",WorkflowCommand.SaveDraft(id,1,DraftContent("2026-09-08",players,listOf(PaymentInput("a",100)))))
            if(post) service.execute(member(),"post-$id",WorkflowCommand.PostDraft(id,2))
            if(cancel) service.execute(member(),"cancel-$id",WorkflowCommand.CancelDraft(id,3))
        }
        training("a-once",listOf(PlayerInput("a",60),PlayerInput("a",60,true)))
        training("b-once",listOf(PlayerInput("b",60)))
        training("b-twice",listOf(PlayerInput("b",60)))
        repeat(3) { training("s-cancel-$it",listOf(PlayerInput("s",60)),cancel=true) }
        training("s-draft",listOf(PlayerInput("s",60)),post=false)
        // Pending edits do not erase the published attendance of Boris.
        service.execute(member(),"edit-b",WorkflowCommand.SaveDraft("b-twice",3,DraftContent("2026-09-08",
            listOf(PlayerInput("s",60)),listOf(PaymentInput("a",100)))))
        service.execute(member(),"selection",WorkflowCommand.CreateDraft("selection","2026-09-08"))
        open(action=BotAction("add_players",entity="selection"))
        fun choices() = panel().keyboard!!.rows.flatten().filter { it.text.startsWith("⬜") || it.text.startsWith("✅") }.map { it.text }
        assertEquals(listOf("⬜ Борис","⬜ Андрей","⬜ Саша"),choices())
        click("⬜ Саша")
        assertEquals(listOf("⬜ Борис","⬜ Андрей","✅ Саша"),choices())
    }

    @Test fun `training can be posted and edited directly from its card without preview`() {
        seed(); ready(); open(action=BotAction("draft",entity="training"))
        val token=click("Записать тренировку")
        assertEquals(DraftStatus.POSTED,service.draft(member(),"training").status)
        assertEquals(50L,database.balances(group)[ParticipantId("a")])
        clickData(token)
        assertEquals(1,database.history(group).size)
        click("Кто оплатил"); clickStarts("Андрей"); reply("200"); click("К тренировке")
        click("Сохранить изменения")
        assertEquals(DraftStatus.POSTED,service.draft(member(),"training").status)
        assertEquals(100L,database.balances(group)[ParticipantId("a")])
        assertEquals(2,database.history(group).size)
        assertEquals(2,api.sent.count { it.chat.id==chat })
        open(2,BotAction("draft",entity="training"))
        click("Кто оплатил",2); clickStarts("Андрей",2); reply("300",2); click("К тренировке",2)
        click("Сохранить изменения",2)
        assertEquals(150L,database.balances(group)[ParticipantId("a")])
    }

    @Test fun `selection is private until explicit save and closing discards only personal input`() {
        seed(); open(); click("Записать тренировку")
        val before=service.audit(member()).size
        click("⬜ Андрей"); click("⬜ Борис"); click("Дальше: оплаты · 2")
        assertTrue(service.drafts(member(),true).isEmpty())
        assertEquals(before,service.audit(member()).size)
        val privateEditor=bot.state.editors(1,group).single()
        val id=privateEditor.draftId
        assertNull(bot.state.editor(2,group,privateEditor.id))
        bot=TelegramBot(api,SqliteAccountingStore(directory.resolve("bot.sqlite"),clock),api.bot,clock)
        assertEquals(2,input(id).players.size)
        click("Ещё"); click("Сохранить на потом")
        assertEquals(2,service.draft(member(),id).content.players.size)
        assertEquals(before+1,service.audit(member()).size)
        assertTrue(database.history(group).isEmpty())
        click("Игроки"); click("Время всем"); click("2 ч")
        assertTrue(service.draft(member(),id).content.players.all { it.minutes==60L })
        click("К тренировке"); click("Ещё"); click("Закрыть без сохранения")
        assertTrue(bot.state.editors(1,group).isEmpty())
        assertTrue(service.draft(member(),id).content.players.all { it.minutes==60L })
    }

    @Test fun `training without payments cannot be posted from the screen`() {
        seed(); open(); click("Записать тренировку"); click("⬜ Андрей"); click("Дальше: оплаты · 1")
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text=="Записать тренировку" })
        assertTrue(text().contains("Кто оплатил стол?"))
        assertTrue(service.drafts(member(),true).isEmpty())
        assertTrue(database.history(group).isEmpty())
        assertEquals(1,bot.state.editors(1,group).single().content.players.size)
    }

    @Test fun `simultaneous editors cannot overwrite a saved revision and can reload explicitly`() {
        seed(); ready()
        open(action=BotAction("payments",entity="training")); clickStarts("Андрей"); reply("300"); click("К тренировке")
        open(2,BotAction("payments",entity="training")); clickStarts("Андрей",2); reply("400",2); click("К тренировке",2)
        click("Ещё",2)
        click("Ещё"); click("Сохранить на потом")
        click("Сохранить на потом",2)
        assertEquals(300L,service.draft(member(),"training").content.payments.single().amount)
        assertEquals(400L,input(userId=2).payments.single().amount)
        click("Открыть свежую запись",2)
        assertTrue(text(2).contains("Твой ввод сохранён отдельно"))
        click("Загрузить общую версию",2); click("Загрузить общую версию",2)
        assertEquals(300L,input(userId=2).payments.single().amount)
    }

    @Test fun `correcting a training keeps an already recorded repayment`() {
        seed(); ready(cost=600); open(action=BotAction("draft",entity="training")); click("Записать тренировку")
        service.execute(member(2),"return",WorkflowCommand.RecordTransfer("return","b","a",100,"2026-09-08"))
        assertEquals(200L,database.balances(group)[ParticipantId("a")])
        click("Кто оплатил"); clickStarts("Андрей"); reply("800"); click("К тренировке")
        assertEquals(200L,database.balances(group)[ParticipantId("a")])
        assertTrue(text().contains("600 → 800 ₽"))
        val token=click("Сохранить изменения"); clickData(token)
        assertEquals(300L,database.balances(group)[ParticipantId("a")])
        assertEquals(-300L,database.balances(group)[ParticipantId("b")])
        assertEquals(3,database.history(group).size)
    }

    @Test fun `inactive participants are separate in all lists without hiding their debts from accounting`() {
        seed(); ready(); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        service.execute(member(),"inactive",WorkflowCommand.SetActive("a",2,false))
        open(action=BotAction("balances"))
        assertFalse(text().contains("Андрей:"))
        assertTrue(text().contains("им должны 50 ₽"))
        clickStarts("Не ходят")
        assertTrue(text().contains("Андрей: +50 ₽"))
        open(action=BotAction("participants"))
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text=="Андрей" })
        clickStarts("Не ходят"); click("Андрей"); click("К участникам")
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text=="Андрей" })
        open(action=BotAction("add_players",entity="training"))
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text.contains("Андрей") })
        click("Ещё"); clickStarts("Не ходят"); assertNotNull(button("✅ Андрей"))
        click("Дальше: оплаты · 2")
        assertTrue(text().contains("Андрей (не ходит)"))
        assertEquals(50L,database.balances(group)[ParticipantId("a")])
    }

    @Test fun `attendance order is cached across navigation and balances remain fresh`() {
        seed()
        val order=service.attendanceOrder(member())
        assertSame(order,service.attendanceOrder(member()))
        service.execute(member(),"transfer",WorkflowCommand.RecordTransfer("t","a","b",40,"2026-09-08"))
        assertSame(order,service.attendanceOrder(member()))
        service.execute(member(),"rename",WorkflowCommand.RenameParticipant("s",1,"Ааа"))
        assertEquals("s",service.attendanceOrder(member()).first())
        ready(); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        assertEquals(listOf("a","b","s"),service.attendanceOrder(member()))
        service.execute(member(),"cancel",WorkflowCommand.CancelDraft("training",3))
        assertEquals("s",service.attendanceOrder(member()).first())
    }

    @Test fun `guest shares one output line with inviter and full roster is never silently truncated`() {
        repeat(27) { i -> service.execute(member(),"p-$i",WorkflowCommand.AddParticipant("p$i","Игрок %02d".format(i+1))) }
        service.execute(member(),"draft",WorkflowCommand.CreateDraft("many","2026-09-08"))
        service.execute(member(),"save",WorkflowCommand.SaveDraft("many",1,DraftContent("2026-09-08",
            (0..26).map { PlayerInput("p$it",60) }+PlayerInput("p0",30,true),listOf(PaymentInput("p0",100)))))
        open(action=BotAction("draft",entity="many"))
        assertTrue(text().contains("Игрок 01: 1 ч · +1: 0,5 ч"))
        assertEquals(1,text().lines().count { it.contains("Игрок 01") && !it.startsWith("Оплатили:") })
        assertTrue(text().contains("ещё 21"))
        click("Подробности")
        assertTrue(text().contains("Игрок 01: 1 ч · +1: 0,5 ч"))
        repeat(3) { click("Дальше →") }
        assertTrue(text().contains("Игрок 27"))
    }

    @Test fun `same-name participants have distinguishable labels without exposing record IDs`() {
        seed()
        service.execute(member(),"second-sasha",WorkflowCommand.AddParticipant("secret-record-id","Саша"))
        open(action=BotAction("participants"))
        val labels=panel().keyboard!!.rows.flatten().map { it.text }
        assertTrue("Саша (1)" in labels && "Саша (2)" in labels)
        assertFalse(labels.any { it.contains("secret-record-id") })
    }
    @Test fun `simple training flow for twenty-seven members posts after players and payments`() {
        repeat(27) { i -> service.execute(member(),"person-$i",WorkflowCommand.AddParticipant("p$i","Игрок %02d".format(i+1))) }
        open()
        assertEquals(listOf("Записать тренировку","Кто кому должен","Записать перевод","Ещё","Сменить группу"),
            panel().keyboard!!.rows.flatten().map { it.text })
        click("Записать тренировку")
        assertTrue(text().startsWith("🏓 Теннис"))
        (1..6).forEach { click("⬜ Игрок %02d".format(it)) }
        click("Дальше: оплаты · 6")
        assertTrue(text().contains("Кто оплатил стол?"))
        val payers=panel().keyboard!!.rows.flatten().mapNotNull { bot.state.action(it.callbackData!!)?.action }
            .filter { it.field=="payment" }.map { it.participant }
        assertEquals((0..5).map { "p$it" },payers)
        click("Игрок 01"); reply("350")
        assertTrue(text().contains("Всего: 350 ₽"))
        click("Игрок 02"); reply("400")
        assertTrue(text().contains("Всего: 750 ₽"))
        assertTrue(text().contains("Игрок 06: 1 ч"))
        val token=click("Записать тренировку")
        clickData(token)
        val posted=service.drafts(member(),true).single()
        assertEquals(DraftStatus.POSTED,posted.status)
        assertEquals(6,posted.content.players.size)
        assertTrue(posted.content.players.all { it.minutes==60L })
        assertEquals(1,database.history(group).size)
        assertEquals(225L,database.balances(group)[ParticipantId("p0")])
        assertEquals(275L,database.balances(group)[ParticipantId("p1")])
        assertEquals(-125L,database.balances(group)[ParticipantId("p5")])
    }

    private fun secondGroup(): VerifiedGroupMember {
        val otherChat=-100456L
        api.members[otherChat to 900]=TgMember("administrator")
        api.members[otherChat to 1]=TgMember("administrator")
        bot.handle(message("/setup",chatId=otherChat))
        bot.state.register(BotGroup("tg:$otherChat",otherChat,"Личная группа"))
        return VerifiedGroupMember("tg:$otherChat",1,true)
    }

    @Test fun `group switch includes newly configured empty group and keeps existing training separate`() {
        seed(); open(); click("Записать тренировку"); click("⬜ Андрей")
        val original=bot.state.editors(1,group).single()
        val other=secondGroup()
        // Setup alone must not silently redirect an existing personal form.
        assertEquals(group,bot.state.session(1).groupId)
        bot.handle(message("/menu")); click("Сменить группу")
        assertNotNull(button("Личная группа")); assertNotNull(button("Теннис"))
        click("Личная группа"); click("Записать тренировку")
        assertTrue(text().startsWith("🏓 Личная группа"))
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text.contains("Андрей") })
        assertEquals(other.groupId,bot.state.session(1).groupId)
        assertNotNull(button("Добавить игрока"))
        click("Добавить игрока")
        val prompt=api.messages[1L to bot.state.session(1).input!!.promptId]!!
        assertTrue(prompt.text!!.startsWith("🏓 Личная группа"))
        reply("Только здесь")
        assertEquals(listOf("Только здесь"),service.participants(other).map { it.participant.name })
        assertEquals(listOf(PlayerInput("a",60)),bot.state.editor(1,group,original.id)!!.content.players)
        assertEquals(3,service.participants(member()).size)
        bot.handle(message("/groups")); click("Теннис")
        assertTrue(text().startsWith("🏓 Теннис"))
    }

    @Test fun `group chooser hides unvisited groups and rechecks access on selection`() {
        seed(); open(2)
        val other=secondGroup()
        click("Сменить группу",2)
        assertFalse(panel(2).keyboard!!.rows.flatten().any { it.text=="Личная группа" })
        bot.handle(message("/groups"))
        val stale=button("Личная группа").callbackData!!
        api.members[bot.state.group(other.groupId)!!.chatId to 1]=TgMember("left")
        clickData(stale)
        assertTrue(text().contains("Доступ к группе не подтверждён"))
        bot.handle(message("/groups"))
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text=="Личная группа" })
        assertNotNull(button("Теннис"))
    }

    @Test fun `repeat roster and attendance only use current group and repeat names a fixed training`() {
        seed()
        ready("first",600); service.execute(member(),"post-first",WorkflowCommand.PostDraft("first",2))
        val other=secondGroup()
        listOf("a","b","s").forEach { service.execute(other,"add-$it",WorkflowCommand.AddParticipant(it,"Чужой $it")) }
        repeat(3) { service.execute(other,"post-$it",WorkflowCommand.CommitDraft("other-$it",0,
            DraftContent("2026-09-08",listOf(PlayerInput("s",60)),listOf(PaymentInput("s",100))),true)) }
        assertEquals(listOf("a","b","s"),service.attendanceOrder(member()))
        assertEquals("s",service.attendanceOrder(other).first())
        open(); click("Записать тренировку"); click("Ещё")
        val repeatToken=button("Повторить состав за 08.09.2026").callbackData!!
        // Another completed training must not change what the already displayed button repeats.
        service.execute(member(),"newer",WorkflowCommand.CommitDraft("z-later",0,
            DraftContent("2026-09-08",listOf(PlayerInput("s",60)),listOf(PaymentInput("s",100))),true))
        clickData(repeatToken)
        assertEquals(listOf("a","b"),bot.state.editors(1,group).single().content.players.map { it.participantId })
        assertTrue(text().startsWith("🏓 Теннис"))
    }

    @Test fun `payment input retains the page and outside payer filter`() {
        repeat(27) { i -> service.execute(member(),"person-$i",WorkflowCommand.AddParticipant("p$i","Игрок %02d".format(i+1))) }
        open(); click("Записать тренировку"); click("⬜ Игрок 01"); click("Дальше: оплаты · 1")
        click("Оплатил другой человек"); repeat(3) { click("Дальше →") }
        click("Игрок 27"); reply("350")
        assertNotNull(button("Игрок 27 · 350 ₽"))
        assertNotNull(button("Только игроки и оплатившие"))
        click("Записать тренировку")
        assertEquals(350L,database.balances(group)[ParticipantId("p26")])
        assertEquals(-350L,database.balances(group)[ParticipantId("p0")])
    }

    @Test fun `reopening an unchanged card refreshes data saved by another editor`() {
        seed(); ready(cost=600); open(action=BotAction("draft",entity="training"))
        assertFalse(editor().dirty)
        service.execute(member(),"new-content",WorkflowCommand.CommitDraft("training",2,
            service.draft(member(),"training").content.copy(payments=listOf(PaymentInput("a",800))),true))
        open(action=BotAction("draft",entity="training"))
        assertTrue(text().contains("Всего: 800 ₽"))
        assertFalse(text().contains("Твой ввод сохранён отдельно"))
        assertFalse(editor().dirty)
    }

    @Test fun `identical record IDs in two groups keep money history and corrections isolated`() {
        seed()
        val other=secondGroup()
        listOf("a","b").forEach { service.execute(other,"seed-$it",WorkflowCommand.AddParticipant(it,"Другой $it")) }
        service.execute(other,"link-a",WorkflowCommand.LinkSelf("a",1))
        fun content(cost:Long)=DraftContent("2026-09-08",listOf(PlayerInput("a",60),PlayerInput("b",60)),listOf(PaymentInput("a",cost)))
        service.execute(member(),"same-post",WorkflowCommand.CommitDraft("same-training",0,content(600),true))
        service.execute(other,"same-post",WorkflowCommand.CommitDraft("same-training",0,content(1000),true))
        service.execute(member(2),"same-transfer",WorkflowCommand.RecordTransfer("same-transfer","b","a",100,"2026-09-08"))
        service.execute(other,"same-transfer",WorkflowCommand.RecordTransfer("same-transfer","a","b",100,"2026-09-08"))
        assertEquals(200L,database.balances(group)[ParticipantId("a")])
        assertEquals(600L,database.balances(other.groupId)[ParticipantId("a")])
        val otherBalances=database.balances(other.groupId)
        val otherHistory=database.history(other.groupId)
        val otherAudit=service.audit(other)
        open(action=BotAction("history"))
        assertTrue(labels().contains("600 ₽"))
        assertFalse(labels().contains("1000 ₽"))
        service.execute(member(),"cancel",WorkflowCommand.CancelDraft("same-training",service.draft(member(),"same-training").version))
        assertEquals(-100L,database.balances(group)[ParticipantId("a")])
        assertEquals(DraftStatus.POSTED,service.draft(other,"same-training").status)
        assertEquals(otherBalances,database.balances(other.groupId))
        assertEquals(otherHistory,database.history(other.groupId))
        assertEquals(otherAudit,service.audit(other))
        val link=bot.state.link(other.groupId,BotAction("history"))
        bot.handle(message("/start $link"))
        assertTrue(labels().contains("1000 ₽"))
        clickStarts("1. Перевод")
        assertTrue(text().contains("Другой"))
        assertFalse(text().contains("600 ₽") || text().contains("Андрей"))
    }

    @Test fun `same draft ID has separate editors and cross-group editor references are rejected`() {
        seed(); ready("same",600)
        val other=secondGroup()
        service.execute(other,"add",WorkflowCommand.AddParticipant("a","Другой игрок"))
        service.execute(other,"create",WorkflowCommand.CreateDraft("same","2026-09-08"))
        open(action=BotAction("payments",entity="same")); clickStarts("Андрей"); reply("800")
        val first=editor("same")
        val link=bot.state.link(other.groupId,BotAction("draft",entity="same"))
        bot.handle(message("/start $link"))
        val second=bot.state.editors(1,other.groupId).single()
        assertNotEquals(first.id,second.id)
        assertTrue(second.content.players.isEmpty())
        assertTrue(second.content.payments.isEmpty())
        val invalid=bot.state.action(1,other.groupId,BotAction("draft",entity="same",editorId=first.id))
        clickData(invalid)
        assertFalse(text().contains("800") || text().contains("Андрей"))
        assertEquals(first,bot.state.editor(1,group,first.id))
        assertEquals(second,bot.state.editor(1,other.groupId,second.id))
        assertTrue(database.history(group).isEmpty())
        assertTrue(database.history(other.groupId).isEmpty())
    }

    @Test fun `reply from previous group cannot fill a prompt in the current group`() {
        seed(); ready()
        val other=secondGroup()
        service.execute(other,"add",WorkflowCommand.AddParticipant("a","Другой игрок"))
        service.execute(other,"create",WorkflowCommand.CreateDraft("training","2026-09-08"))
        open(action=BotAction("payments",entity="training")); clickStarts("Андрей")
        val oldPrompt=api.messages[1L to bot.state.session(1).input!!.promptId]!!
        val first=editor()
        val link=bot.state.link(other.groupId,BotAction("payments",entity="training",showAll=true))
        bot.handle(message("/start $link")); click("Другой игрок")
        val currentPrompt=bot.state.session(1).input!!.promptId
        bot.handle(message("900",reply=oldPrompt))
        assertEquals(currentPrompt,bot.state.session(1).input!!.promptId)
        assertEquals(first,bot.state.editor(1,group,first.id))
        assertTrue(bot.state.editors(1,other.groupId).single().content.payments.isEmpty())
        reply("250")
        assertEquals(250L,bot.state.editors(1,other.groupId).single().content.payments.single().amount)
        assertEquals(100L,editor().content.payments.single().amount)
        assertTrue(database.history(group).isEmpty())
        assertTrue(database.history(other.groupId).isEmpty())
    }

    @Test fun `group member who did not play can correct a posted training`() {
        seed(); ready(cost=600); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        // User 3 belongs to the chat, has no participant profile, and did not play.
        open(3,BotAction("payments",entity="training"))
        clickStarts("Андрей",3); reply("800",3)
        click("Сохранить изменения",3)
        assertEquals(400L,database.balances(group)[ParticipantId("a")])
        assertEquals(800L,service.draft(member(),"training").publishedContent!!.payments.single().amount)
        assertEquals(3L,service.audit(member()).last().actorUserId)
        click("Ещё",3)
        assertFalse(panel(3).keyboard!!.rows.flatten().any { it.text=="Отменить тренировку" })
    }

    @Test fun `being a member of another bot group grants no access to a posted training`() {
        seed(); ready(cost=600); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        val other=secondGroup()
        val otherChat=bot.state.group(other.groupId)!!.chatId
        api.members[otherChat to 3]=TgMember("member")
        val ownLink=bot.state.link(other.groupId,BotAction("menu"))
        bot.handle(message("/start $ownLink",3))
        assertTrue(text(3).startsWith("🏓 Личная группа"))
        api.members[chat to 3]=TgMember("left")
        val before=service.audit(member())
        open(3,BotAction("payments",entity="training"))
        assertTrue(text(3).contains("Доступ к группе не подтверждён"))
        assertFalse(text(3).contains("600") || text(3).contains("Андрей"))
        assertEquals(before,service.audit(member()))
        assertEquals(300L,database.balances(group)[ParticipantId("a")])
    }

    @Test fun `leaving training group after editing prevents saving with old button`() {
        seed(); ready(cost=600); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        open(3,BotAction("payments",entity="training")); clickStarts("Андрей",3); reply("800",3)
        val save=button("Сохранить изменения",3).callbackData!!
        api.members[chat to 3]=TgMember("left")
        clickData(save,3)
        assertTrue(text(3).contains("Доступ к группе не подтверждён"))
        assertEquals(600L,service.draft(member(),"training").publishedContent!!.payments.single().amount)
        assertEquals(1,database.history(group).size)
    }

    @Test fun `unfinished list paginates all personal forms and shared drafts without duplicates`() {
        seed()
        repeat(9) { ready("shared-$it") }
        val editing=DraftEditing(service,bot.state)
        repeat(10) { editing.open(member(),"private-$it","private-$it","2026-09-08") }
        val existing=editing.open(member(),"shared-0","existing")
        bot.state.edit(1,group,existing.id,existing.revision,9000) { it.copy(content=it.content.copy(payments=listOf(PaymentInput("a",200)))) }
        open(action=BotAction("drafts"))
        fun targets()=panel().keyboard!!.rows.flatten().mapNotNull { bot.state.action(it.callbackData!!)?.action }
            .filter { it.kind=="draft" }.map { it.entity }
        assertFalse(text().contains("Пока пусто"))
        assertTrue(text().contains("Страница 1 из 3"))
        assertEquals(8,targets().size)
        val ids=targets().toMutableList()
        click("Дальше →"); assertEquals(8,targets().size); ids+=targets()
        click("Дальше →"); assertEquals(3,targets().size); ids+=targets()
        assertEquals(19,ids.toSet().size)
        assertEquals(1,ids.count { it=="shared-0" })
        assertTrue((0..9).all { "private-$it" in ids })
        assertFalse(panel().keyboard!!.rows.flatten().any { it.text=="Дальше →" || it.text=="Записать тренировку" })
    }

    @Test fun `all trainings paginates finished records and can return to unfinished`() {
        seed()
        repeat(17) { ready("finished-$it"); service.execute(member(),"post-$it",WorkflowCommand.PostDraft("finished-$it",2)) }
        open(action=BotAction("drafts"))
        assertTrue(text().contains("Пока пусто"))
        click("Показать все")
        fun count()=panel().keyboard!!.rows.flatten().count { bot.state.action(it.callbackData!!)?.action?.kind=="draft" }
        assertEquals(8,count()); assertTrue(text().contains("Страница 1 из 3"))
        click("Дальше →"); assertEquals(8,count())
        click("Дальше →"); assertEquals(1,count())
        click("Только незавершённые")
        assertTrue(text().contains("Пока пусто")); assertEquals(0,count())
    }

    @Test fun `growing participant lists show every player through pagination`() {
        repeat(27) { i -> service.execute(member(),"person-$i",WorkflowCommand.AddParticipant("p$i","Игрок %02d".format(i+1))) }
        service.execute(member(),"training",WorkflowCommand.CommitDraft("many",0,DraftContent("2026-09-08",
            (0..26).map { PlayerInput("p$it",60) },listOf(PaymentInput("p0",2700)))))
        val form=TransferForm("test",date="2026-09-08")
        listOf(BotAction("participants"),BotAction("balances"),BotAction("players",entity="many"),
            BotAction("payments",entity="many"),BotAction("training_details",entity="many"),BotAction("preview",entity="many"),
            BotAction("choose_from",form=form),BotAction("choose_to",form=form)).forEach { action ->
            open(action=action)
            val seen=mutableSetOf<String>()
            var pages=0
            do {
                val content=text()+"\n"+labels()
                seen+=Regex("Игрок [0-9]{2}").findAll(content).map { it.value }.toSet()
                assertTrue(text().length<3500,action.kind)
                pages++
                val next=panel().keyboard!!.rows.flatten().any { it.text=="Дальше →" }
                if(next) click("Дальше →")
            } while(next && pages<10)
            assertEquals(4,pages,action.kind)
            assertEquals(27,seen.size,action.kind)
        }
    }

    @Test fun `similar transfers and recovery lists are not silently truncated`() {
        seed()
        repeat(10) { service.execute(member(),"pay-$it",WorkflowCommand.RecordTransfer("t$it","a","b",100,"2026-09-08",allowSimilar=true)) }
        recordViaUi()
        fun records(kind:String)=panel().keyboard!!.rows.flatten().mapNotNull { bot.state.action(it.callbackData!!)?.action }.filter { it.kind==kind }
        assertEquals(8,records("transfer").size)
        click("Дальше →"); assertEquals(2,records("transfer").size)
        assertTrue(text().contains("Похожий перевод"))
        click("Это ещё один перевод")
        assertEquals(11,database.history(group).size)
        repeat(10) {
            bot.state.sending("training:$group:missing-$it",group,chat)
            bot.state.deliveryStatus("training:$group:missing-$it","FAILED")
        }
        open(action=BotAction("recovery")); assertEquals(8,records("recover_confirm").size)
        click("Дальше →"); assertEquals(2,records("recover_confirm").size)
        click("Карточка тренировки"); click("Назад")
        assertEquals(2,records("recover_confirm").size)
        assertNotNull(button("2/2"))
    }

    @Test fun `historical before and after content is fully paginated and keeps message short`() {
        repeat(27) { i -> service.execute(member(),"person-$i",WorkflowCommand.AddParticipant("p$i","Игрок %02d".format(i+1))) }
        val content=DraftContent("2026-09-08",(0..26).map { PlayerInput("p$it",60) },(0..26).map { PaymentInput("p$it",100) })
        service.execute(member(),"original",WorkflowCommand.CommitDraft("many",0,content,true))
        service.execute(member(),"change",WorkflowCommand.CommitDraft("many",service.draft(member(),"many").version,
            content.copy(players=content.players.map { if(it.participantId=="p26") it.copy(minutes=90) else it }),true))
        val event=service.audit(member()).last()
        open(action=BotAction("audit",entity=event.id.toString(),back="changes",page=2))
        repeat(6) { assertTrue(text().length<3500); click("Дальше →") }
        assertTrue(text().contains("Игрок 27: 1 ч"))
        assertTrue(text().contains("Игрок 27: 1,5 ч"))
        assertEquals(2,text().lines().count { it=="Игрок 27: 100 ₽" })
        click("К изменениям")
        assertTrue(text().contains("Все изменения"))
        assertTrue(panel().keyboard!!.rows.flatten().mapNotNull { bot.state.action(it.callbackData!!)?.action }.filter { it.kind=="audit" }.all { it.page==2 })
    }

    @Test fun `history remains short with many records and long participant names`() {
        seed()
        service.execute(member(),"long-a",WorkflowCommand.RenameParticipant("a",2,"А".repeat(100)))
        service.execute(member(2),"long-b",WorkflowCommand.RenameParticipant("b",2,"Б".repeat(100)))
        repeat(40) { service.execute(member(),"pay-$it",WorkflowCommand.RecordTransfer("long-$it","a","b",it+1L,"2026-09-08")) }
        open(action=BotAction("history"))
        repeat(5) { index ->
            assertTrue(text().length<200)
            assertEquals(8,panel().keyboard!!.rows.flatten().count { bot.state.action(it.callbackData!!)?.action?.kind=="transfer" })
            if(index<4) click("Дальше →")
        }
        clickStarts("33. Перевод")
        assertTrue(text().contains("А".repeat(100)))
        assertTrue(text().contains("Б".repeat(100)))
    }

    @Test fun `bot admin and organizer rights are checked separately for each group`() {
        seed(); ready(); service.execute(member(),"post",WorkflowCommand.PostDraft("training",2))
        val otherChat=-100789L
        api.members[otherChat to 900]=TgMember("administrator")
        api.members[otherChat to 3]=TgMember("administrator")
        bot.handle(message("/setup",userId=3,chatId=otherChat))
        val other=VerifiedGroupMember("tg:$otherChat",3,true)
        assertTrue(service.canManage(other))
        assertFalse(service.canManage(member(3)))
        open(3,BotAction("draft_more",entity="training"))
        assertFalse(labels(3).contains("Отменить тренировку"))
        val before=service.audit(member())
        val forbidden=bot.state.action(3,group,BotAction("apply",entity="training",back="draft",
            command=WorkflowCommand.CancelDraft("training",service.draft(member(),"training").version)))
        clickData(forbidden,3)
        assertTrue(text(3).contains("не хватает прав"))
        assertEquals(before,service.audit(member()))
        assertEquals(DraftStatus.POSTED,service.draft(member(),"training").status)
        // Losing bot admin rights in the second group does not affect the first.
        api.members[otherChat to 900]=TgMember("member")
        open(3,BotAction("plan"))
        assertFalse(text(3).contains("Доступ к группе не подтверждён"))
        val otherLink=bot.state.link(other.groupId,BotAction("menu"))
        bot.handle(message("/start $otherLink",3))
        assertTrue(text(3).contains("Доступ к группе не подтверждён"))
    }

}
