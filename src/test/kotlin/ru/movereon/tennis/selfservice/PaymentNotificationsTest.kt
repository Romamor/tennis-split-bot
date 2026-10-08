package ru.movereon.tennis.selfservice

import java.nio.file.Path
import java.time.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import kotlin.test.*

class PaymentNotificationsTest {
    @TempDir lateinit var dir:Path
    private val fake=FakeTelegramApi()
    private var now=Instant.parse("2026-10-08T10:00:00Z")
    private val clock=object:Clock() {
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=Clock.fixed(now,zone)
    }
    private var sendFailure:TelegramFailure?=null
    private var editFailure:TelegramFailure?=null
    private var attempts=0
    private var watchedNotice:Long?=null
    private var handling=false
    private val notificationDeletionPhases=mutableListOf<Boolean>()
    private val api=object:TelegramApi by fake {
        override fun delete(chatId:Long,messageId:Long) {
            if(chatId==2L && messageId==watchedNotice) notificationDeletionPhases+=handling
            fake.delete(chatId,messageId)
        }
        override fun sendRich(chatId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?):TgMessage {
            if(text.startsWith("Новый перевод")) { attempts++;sendFailure?.let { throw it } }
            return fake.sendRich(chatId,text,html,keyboard,photoId)
        }
        override fun editRich(chatId:Long,messageId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?) {
            editFailure?.let { throw it };fake.editRich(chatId,messageId,text,html,keyboard,photoId)
        }
    }
    private lateinit var bot:SelfServiceBot
    private var sequence=1000L
    private val names=mapOf(1L to "Роман",2L to "Лена",3L to "Другой")
    private fun setup(startRecipient:Boolean=true) {
        bot=SelfServiceBot(api,Database(dir.resolve("notice.sqlite")),fake.bot,clock)
        for(group in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(group,"Группа ${-group}","Europe/Moscow"))
            for(user in 1L..3L) {
                bot.service.remember(Account(user,names.getValue(user)))
                bot.service.rememberMembership(group,user,true);fake.members[group to user]=TgMember("member")
            }
        }
        if(startRecipient) bot.handle(TgUpdate(sequence++,TgMessage(sequence++,TgChat(2,"private"),TgUser(2,firstName="Лена"),"/start")))
    }
    private fun run(command:SettlementCommand,user:Long=1,group:Long=-1)=bot.service.execute(Access(group,user,user==3L),"request-${sequence++}",command)
    private fun send(id:String="p",group:Long=-1,amount:Long=300)=run(SettlementCommand.SendOtherPayment(id,2,amount),group=group)
    private fun notice(group:Long=-1,id:String="p"):TgMessage {
        val delivery=bot.state.delivery("payment-notice:$group:$id")!!
        return fake.messages.getValue(2L to delivery.message!!)
    }
    private fun click(message:TgMessage,text:String,user:Long=2) {
        val button=message.keyboard!!.rows.flatten().single { it.text.contains(text) }
        bot.handle(TgUpdate(sequence++,callback=TgCallback("click-${sequence++}",TgUser(user,firstName=names.getValue(user)),message,button.callbackData)))
    }
    private fun mainMenu()=fake.messages.getValue(2L to bot.state.delivery("personal:2:2")!!.message!!)

    @Test fun `queued notice deletion never runs inside a button or message handler`() {
        setup();send();bot.maintain();val notification=notice();watchedNotice=notification.id
        run(SettlementCommand.CancelPayment("p",1))
        bot.state.removePaymentNotice("payment-notice:-1:p")
        handling=true
        try {
            bot.handle(TgUpdate(sequence++,TgMessage(sequence++,TgChat(2,"private"),TgUser(2,firstName="Лена"),"/start")))
        } finally { handling=false }
        assertTrue(notificationDeletionPhases.isEmpty(),"Slow notice deletion must not delay foreground processing")
        assertTrue(bot.state.pendingNoticeRemovals().isNotEmpty())
        assertTrue(fake.messages.containsKey(2L to notification.id))
        bot.maintain()
        assertEquals(listOf(false),notificationDeletionPhases)
        assertTrue(bot.state.pendingNoticeRemovals().isEmpty())
        assertFalse(fake.messages.containsKey(2L to notification.id))
        assertEquals(PaymentStatus.CANCELLED,bot.service.transfer(Access(-1,2),"p").status)
    }

    @Test fun `one separate recipient notice does not replace menu or draft and opening never confirms money`() {
        setup();val menu=mainMenu();val form=InputForm("title",0,title="Черновик")
        bot.state.session(2,2,0,form);send()
        assertNull(bot.state.delivery("payment-notice:-1:p"));assertEquals(1,bot.nextPollTimeout(25))
        val balances=bot.service.balances(Access(-1,1))
        bot.maintain()
        val notification=notice()
        assertNotEquals(menu.id,notification.id);assertEquals(menu,mainMenu());assertEquals(form,bot.state.form(2,2))
        assertContains(notification.text!!,"Роман отметил перевод тебе: 300 ₽")
        assertContains(notification.text!!,"Группа: Группа 1")
        assertContains(fake.richMessages.getValue(2L to notification.id),TrainingCard.profileLink(1,"Роман"))
        assertEquals(balances,bot.service.balances(Access(-1,1)))
        bot.state.session(2,2,0,null)
        click(notification,"Подтвердить получение")
        assertContains(mainMenu().text!!,"Деньги пришли?")
        assertTrue(fake.messages.containsKey(2L to notification.id))
        val panel=mainMenu().id
        repeat(4) { click(notification,"Подтвердить получение");assertEquals(panel,mainMenu().id) }
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
        assertEquals(balances,bot.service.balances(Access(-1,1)))
        click(mainMenu(),"Да, получил");bot.maintain()
        assertEquals(PaymentStatus.ACTIVE,bot.service.transfer(Access(-1,2),"p").status)
        assertEquals("BLOCKED",bot.state.delivery("payment-notice:-1:p")!!.status)
        assertFalse(fake.messages.containsKey(2L to notification.id))
        assertTrue(mainMenu().id>notification.id)
        assertTrue(mainMenu().text!!.startsWith("Перевод учтён"))
        assertContains(bot.service.database.verify(),"целостность в порядке")
    }
    @Test fun `duplicate request and process restart do not resend notification`() {
        setup();val command=SettlementCommand.SendOtherPayment("p",2,300)
        bot.service.execute(Access(-1,1),"once",command);bot.service.execute(Access(-1,1),"once",command)
        bot.maintain();val id=notice().id
        repeat(3) { bot.maintain() }
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(id,notice().id);assertEquals(1,attempts)
    }
    @Test fun `edits update unread notice opening removes it and stale confirmation never posts money`() {
        setup();send(amount=100);bot.maintain();val first=notice()
        run(SettlementCommand.EditPaymentAmount("p",1,150));bot.maintain()
        assertEquals(first.id,notice().id);assertContains(notice().text!!,"150 ₽");assertEquals(1,attempts)
        click(first,"Подтвердить получение")
        assertEquals("SENT",bot.state.delivery("payment-notice:-1:p")!!.status);assertTrue(fake.messages.containsKey(2L to first.id))
        assertContains(mainMenu().text!!,"150 ₽");val oldConfirmation=mainMenu()
        run(SettlementCommand.EditPaymentAmount("p",2,200));bot.maintain()
        click(oldConfirmation,"Да, получил")
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
        run(SettlementCommand.CancelPayment("p",3));bot.maintain()
        assertEquals(1,attempts);assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
    }
    @Test fun `groups with identical transfer ids have separate notifications`() {
        setup();send(group=-1);send(group=-2);bot.maintain();bot.maintain()
        assertNotEquals(notice(-1).id,notice(-2).id)
        assertContains(notice(-1).text!!,"Группа 1");assertContains(notice(-2).text!!,"Группа 2")
    }
    @Test fun `no notices for admin entries old transfers already received or never started private chats`() {
        setup(false);send();bot.maintain();assertEquals(0,attempts)
        bot.state.session(2,2,0,null);run(SettlementCommand.EditPaymentAmount("p",1,200));bot.maintain();assertEquals(0,attempts)
        run(SettlementCommand.RecordAdminPayment("admin",1,2,50),user=3);bot.maintain();assertEquals(0,attempts)
        send("received");run(SettlementCommand.ReceivePayment("received",1),user=2);bot.maintain();assertEquals(0,attempts)
        assertTrue(bot.state.pendingPaymentNotices().isEmpty())
    }
    @Test fun `recipient blocked bot does not block finances or repeat failed notifications`() {
        setup();send();fake.failChat=2;bot.maintain();bot.maintain()
        assertEquals(1,attempts);assertEquals("BLOCKED",bot.state.delivery("payment-notice:-1:p")!!.status)
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
        assertTrue(bot.state.pendingPaymentNotices().isEmpty());assertContains(bot.service.database.verify(),"целостность в порядке")
    }
    @Test fun `uncertain accepted send is not duplicated after restart`() {
        setup();send();fake.acceptThenFail={ chat,text -> chat==2L && text.startsWith("Новый перевод") }
        bot.maintain();assertEquals("UNKNOWN",bot.state.delivery("payment-notice:-1:p")!!.status)
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertEquals(1,attempts);assertEquals(1,fake.sent.count { it.text!!.startsWith("Новый перевод") })
    }
    @Test fun `rate limited delivery retries outside money transaction and preserves queue`() {
        setup();send();sendFailure=TelegramFailure(FailureKind.RETRY_LATER,429,1)
        assertFailsWith<TelegramFailure> { bot.maintain() }
        assertEquals("RETRY",bot.state.delivery("payment-notice:-1:p")!!.status)
        assertEquals(1,bot.state.pendingPaymentNotices().size)
        sendFailure=null;bot.maintain();assertEquals(1,attempts)
        now=now.plusSeconds(1);bot.maintain();assertEquals(2,attempts);assertEquals("SENT",bot.state.delivery("payment-notice:-1:p")!!.status)
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
    }
    @Test fun `unchanged edit keeps live confirmation button and deleted notice is not recreated`() {
        setup();send();bot.maintain();val original=notice()
        run(SettlementCommand.EditPaymentAmount("p",1,400));run(SettlementCommand.EditPaymentAmount("p",2,300));editFailure=TelegramFailure(FailureKind.NOT_MODIFIED,400)
        bot.maintain();assertEquals("SENT",bot.state.delivery("payment-notice:-1:p")!!.status)
        val token=original.keyboard!!.rows.single().single().callbackData!!.removePrefix("n:")
        assertNotNull(bot.state.button(token));editFailure=null
        fake.delete(2,original.id);run(SettlementCommand.EditPaymentAmount("p",3,500));bot.maintain()
        assertEquals(1,attempts);assertTrue(bot.state.pendingPaymentNotices().isEmpty())
    }
    @Test fun `unread notice is deleted on external confirmation or administrator cancellation`() {
        setup();send();bot.maintain();val confirmed=notice()
        run(SettlementCommand.ReceivePayment("p",1),user=2);bot.maintain()
        assertFalse(fake.messages.containsKey(2L to confirmed.id))
        assertTrue(bot.state.pendingNoticeRemovals().isEmpty())
        send("cancelled",amount=350);bot.maintain();val cancelled=notice(id="cancelled")
        run(SettlementCommand.CancelPayment("cancelled",1),user=3);bot.maintain()
        assertFalse(fake.messages.containsKey(2L to cancelled.id))
        assertEquals(PaymentStatus.CANCELLED,bot.service.transfer(Access(-1,2),"cancelled").status)
        assertContains(bot.service.database.verify(),"целостность в порядке")
    }
    @Test fun `twelve hour lifetime starts on delivery and editing never prolongs it`() {
        setup();send();now=now.plusSeconds(3600);bot.maintain();val first=notice()
        now=now.plusSeconds(11*3600);run(SettlementCommand.EditPaymentAmount("p",1,500));bot.maintain()
        assertTrue(fake.messages.containsKey(2L to first.id))
        now=now.plusSeconds(3600);bot.maintain()
        assertFalse(fake.messages.containsKey(2L to first.id));assertTrue(bot.state.pendingNoticeRemovals().isEmpty())
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
        bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain();assertEquals(1,attempts)
    }
    @Test fun `failed deletion survives restart and clears notification without requiring a live menu`() {
        setup();send();bot.maintain();val first=notice()
        fake.deleteFailure=TelegramFailure(FailureKind.UNCERTAIN)
        run(SettlementCommand.CancelPayment("p",1));bot.maintain()
        assertTrue(bot.state.pendingNoticeRemovals().isNotEmpty())
        bot.state.forgetDelivery("personal:2:2")
        fake.deleteFailure=null;bot=SelfServiceBot(api,bot.service.database,fake.bot,clock);bot.maintain()
        assertFalse(fake.messages.containsKey(2L to first.id));assertTrue(bot.state.pendingNoticeRemovals().isEmpty())
        assertEquals(1,attempts)
    }
    @Test fun `accepted uncertain notice can be removed on click without respawning`() {
        setup();send();fake.acceptThenFail={ chat,text -> chat==2L && text.startsWith("Новый перевод") }
        bot.maintain();val first=fake.sent.last { it.text!!.startsWith("Новый перевод") }
        click(first,"Подтвердить получение");bot.maintain()
        assertTrue(fake.messages.containsKey(2L to first.id));assertEquals(1,attempts)
        run(SettlementCommand.CancelPayment("p",1));bot.maintain()
        assertFalse(fake.messages.containsKey(2L to first.id))
        assertEquals(PaymentStatus.CANCELLED,bot.service.transfer(Access(-1,2),"p").status)
    }
    @Test fun `blocked notification cleanup retries after recipient unblocks the bot`() {
        setup();send();bot.maintain();val first=notice()
        fake.deleteFailure=TelegramFailure(FailureKind.REJECTED,403)
        run(SettlementCommand.CancelPayment("p",1));bot.maintain()
        assertEquals("BLOCKED",bot.state.pendingNoticeRemovals().single().status)
        fake.deleteFailure=null;now=now.plusSeconds(60);bot.maintain()
        assertFalse(fake.messages.containsKey(2L to first.id));assertTrue(bot.state.pendingNoticeRemovals().isEmpty())
        assertEquals(1,attempts)
    }
    @Test fun `when Telegram refuses old deletion notification loses all obsolete data and controls`() {
        setup();send();bot.maintain();val first=notice()
        fake.deleteFailure=TelegramFailure(FailureKind.REJECTED,400)
        now=now.plusSeconds(49*3600);bot.maintain()
        val closed=fake.messages.getValue(2L to first.id)
        assertTrue(closed.keyboard!!.rows.isEmpty());assertFalse(closed.text!!.contains("300 ₽"))
        assertTrue(bot.state.pendingNoticeRemovals().isEmpty())
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
    }
    @Test fun `another participant cannot use recipient notification or change money`() {
        setup();send();bot.maintain();val first=notice()
        click(first,"Подтвердить получение",user=3)
        assertTrue(fake.messages.containsKey(2L to first.id))
        assertEquals(PaymentStatus.REVIEW,bot.service.transfer(Access(-1,2),"p").status)
        assertTrue(bot.service.balances(Access(-1,1)).values.all { it==0L })
    }

}
