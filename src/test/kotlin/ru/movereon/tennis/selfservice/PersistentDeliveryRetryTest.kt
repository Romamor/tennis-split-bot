package ru.movereon.tennis.selfservice

import java.nio.file.Path
import java.time.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import ru.movereon.tennis.telegram.*
import kotlin.test.*

class PersistentDeliveryRetryTest {
    @TempDir lateinit var dir:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-11-06T12:00:00Z")
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
    }
    private val clock=TestClock()
    private val limited=TelegramFailure(FailureKind.RETRY_LATER,429,3600)
    private fun setup(file:String):Database=Database(dir.resolve(file)).also { db ->
        db.write { c ->
            sqlUpdate(c,"INSERT INTO users(id,first_name) VALUES(1,'Synthetic participant')")
            sqlUpdate(c,"INSERT INTO groups(id,title,time_zone) VALUES(-1,'Synthetic group','Europe/Moscow')")
            sqlUpdate(c,"INSERT INTO group_users(group_id,user_id,present) VALUES(-1,1,1)")
        }
    }
    private fun ready(state:InteractionStore,queue:String):List<String> = when(queue) {
        "cards" -> state.pendingCards().map { "training:${it.first}:${it.second}" }
        "pins" -> state.pendingPins().map { it.first }
        "unpins" -> state.pendingUnpins().map { it.first }
        "retired" -> state.retiredPrivateMenus().map { it.key }
        "notices" -> state.pendingNoticeRemovals().map { it.key }
        else -> error("Unknown queue")
    }

    @ParameterizedTest @ValueSource(strings=["cards","pins","unpins","retired","notices"])
    fun `hour long retries survive reopening and are filtered before queue limits`(queue:String) {
        val db=setup("$queue.sqlite")
        var state=InteractionStore(db,clock)
        val keys=(0..101).map { index -> when(queue) {
            "retired" -> "retired:1:${index+1}"
            "notices" -> "notice-remove:1:${index+1}"
            else -> "training:-1:work-$index"
        } }
        db.write { c ->
            if(queue=="retired") sqlUpdate(c,"INSERT INTO bot_deliveries(delivery_key,chat_id,user_id,message_id,status) VALUES('personal:1:1',1,1,999,'SENT')")
            keys.forEachIndexed { index,key ->
                if(queue in setOf("cards","pins","unpins")) {
                    sqlUpdate(c,"INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,created_by,created_at) VALUES(-1,?,'Synthetic training','2026-11-06','18:30',?,1,1,'now')","work-$index",if(queue=="unpins") "CLOSED" else "OPEN")
                    if(queue=="cards") sqlUpdate(c,"INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,payload_json,after_json,result_version,occurred_at,needs_delivery) VALUES(-1,?,1,'CreateTraining',?,'{}','{}',1,'now',1)","work-$index","work-$index")
                }
                val private=queue in setOf("retired","notices")
                sqlUpdate(c,"INSERT INTO bot_deliveries(delivery_key,group_id,chat_id,user_id,message_id,status,pin_status) VALUES(?,?,?,?,?,?,?)",
                    key,if(private) null else -1,if(private) 1 else -1,if(private) 1 else null,index+1,
                    if(queue in setOf("pins","unpins")) "SENT" else "RETRY",
                    when(queue) { "pins"->"PENDING";"unpins"->"UNPIN_PENDING";else->"NONE" })
            }
        }
        val deadline=clock.millis()+3_600_000
        val pin=queue in setOf("pins","unpins")
        keys.dropLast(1).forEach { state.retryDelivery(it,limited,pin=pin) }
        state=InteractionStore(Database(db.path),clock)
        assertEquals(listOf(keys.last()),ready(state,queue),"Deferred rows must not consume the query limit")
        state.database.read { c ->
            val column=if(pin) "pin_retry_at" else "retry_at"
            assertEquals(deadline,sqlQuery(c,"SELECT $column FROM bot_deliveries WHERE delivery_key=?",keys.first()) { it.getLong(1) }.single())
        }
        clock.now=clock.now.plusMillis(3_599_999)
        assertEquals(listOf(keys.last()),ready(state,queue))
        clock.now=clock.now.plusMillis(1)
        assertTrue(ready(state,queue).any { it in keys.dropLast(1) },"Retry must become eligible at the exact persisted deadline")
    }

    @Test fun `delivery and pin deadlines are independent and explicit delays are never capped`() {
        val db=setup("independent.sqlite");val state=InteractionStore(db,clock)
        state.sending("group-menu:-1",-1,-1,null);state.deliveryResult("group-menu:-1","SENT",42)
        state.pinStatus("group-menu:-1","PENDING")
        state.retryDelivery("group-menu:-1",limited)
        state.retryDelivery("group-menu:-1",TelegramFailure(FailureKind.RETRY_LATER,429,7200),pin=true)
        state.clearDeliveryRetry("group-menu:-1")
        db.read { c ->
            assertEquals(0L to clock.millis()+7_200_000,sqlQuery(c,"SELECT retry_at,pin_retry_at FROM bot_deliveries") { it.getLong(1) to it.getLong(2) }.single())
        }
        assertTrue(state.pendingPins().isEmpty())
        state.clearDeliveryRetry("group-menu:-1",pin=true)
        assertEquals(listOf("group-menu:-1"),state.pendingPins().map { it.first })
        state.retryDelivery("group-menu:-1",TelegramFailure(FailureKind.UNCERTAIN),defaultSeconds=9)
        db.read { c -> assertEquals(clock.millis()+9000,sqlQuery(c,"SELECT retry_at FROM bot_deliveries") { it.getLong(1) }.single()) }
        assertEquals(2_147_483_647_000L,RetryPolicy.delayMillis(TelegramFailure(FailureKind.RETRY_LATER,429,Int.MAX_VALUE)))
    }

    @Test fun `card rate limit waits the full hour across bot restart while another group continues`() {
        val fake=FakeTelegramApi();var blocked=true;var attempts=0
        val api=object:TelegramApi by fake {
            override fun sendRich(chatId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?):TgMessage {
                if(chatId==-1L) { attempts++;if(blocked) throw limited }
                return fake.sendRich(chatId,text,html,keyboard,photoId)
            }
        }
        val file=dir.resolve("bot.sqlite");var bot=SelfServiceBot(api,Database(file),fake.bot,clock)
        bot.service.remember(Account(1,"Synthetic participant"))
        for(group in listOf(-1L,-2L)) {
            bot.service.register(SettlementGroup(group,"Synthetic group","Europe/Moscow"))
            bot.service.rememberMembership(group,1,true)
            bot.service.execute(Access(group,1,true),"create",SettlementCommand.CreateTraining("training","Synthetic training","2026-11-06","18:30"))
        }
        bot.maintain();assertEquals(1,attempts)
        assertEquals("SENT",bot.state.delivery("training:-2:training")?.status)
        blocked=false;bot=SelfServiceBot(api,Database(file),fake.bot,clock)
        bot.maintain();assertEquals(1,attempts)
        clock.now=clock.now.plusMillis(3_599_999);bot.maintain();assertEquals(1,attempts)
        clock.now=clock.now.plusMillis(1);bot.maintain();assertEquals(2,attempts)
        assertEquals("SENT",bot.state.delivery("training:-1:training")?.status)
        assertTrue(bot.state.pendingCards().isEmpty())
    }

    @Test fun `payment notice retry survives restart without hiding the next recipient`() {
        val fake=FakeTelegramApi();var blocked=true;var attempts=0
        val api=object:TelegramApi by fake {
            override fun sendRich(chatId:Long,text:String,html:String,keyboard:TgKeyboard,photoId:String?):TgMessage {
                if(chatId==2L && text.startsWith("Новый перевод")) { attempts++;if(blocked) throw limited }
                return fake.sendRich(chatId,text,html,keyboard,photoId)
            }
        }
        val file=dir.resolve("notice.sqlite");var bot=SelfServiceBot(api,Database(file),fake.bot,clock)
        bot.service.register(SettlementGroup(-1,"Synthetic group","Europe/Moscow"))
        for(user in 1L..3L) {
            bot.service.remember(Account(user,"Synthetic participant $user"));bot.service.rememberMembership(-1,user,true)
            bot.state.session(user,user,0,null)
        }
        bot.service.execute(Access(-1,1),"first",SettlementCommand.SendOtherPayment("first",2,100))
        bot.service.execute(Access(-1,1),"second",SettlementCommand.SendOtherPayment("second",3,200))
        bot.maintain();assertEquals(1,attempts)
        assertEquals(listOf("second"),bot.state.pendingPaymentNotices().map { it.transfer })
        assertEquals("RETRY",bot.state.delivery("payment-notice:-1:first")?.status)
        blocked=false;bot=SelfServiceBot(api,Database(file),fake.bot,clock)
        bot.maintain();assertEquals(1,attempts)
        assertEquals("SENT",bot.state.delivery("payment-notice:-1:second")?.status)
        clock.now=clock.now.plusMillis(3_599_999);bot.maintain();assertEquals(1,attempts)
        clock.now=clock.now.plusMillis(1);bot.maintain();assertEquals(2,attempts)
        assertEquals("SENT",bot.state.delivery("payment-notice:-1:first")?.status)
        assertTrue(bot.state.pendingPaymentNotices().isEmpty())
    }
}
