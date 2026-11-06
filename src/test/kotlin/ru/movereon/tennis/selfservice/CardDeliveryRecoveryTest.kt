package ru.movereon.tennis.selfservice

import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.storage.sqlQuery
import ru.movereon.tennis.telegram.FakeTelegramApi
import kotlin.test.*

class CardDeliveryRecoveryTest {
    @TempDir lateinit var dir:Path
    private class Fixture(path:Path) {
        val fake=FakeTelegramApi()
        var bot=SelfServiceBot(fake,Database(path),fake.bot)
        init {
            bot.service.remember(Account(1,"Synthetic participant"))
            for(group in listOf(-1L,-2L)) {
                bot.service.register(SettlementGroup(group,"Synthetic group $group","Europe/Moscow"))
                bot.service.rememberMembership(group,1,true)
            }
        }
        fun create(group:Long,id:String) {
            bot.service.execute(Access(group,1,true),"create:$id",
                SettlementCommand.CreateTraining(id,"Synthetic training","2026-11-06","18:30"))
        }
        fun blockedCards(status:String) {
            repeat(20) { index ->
                val id="old-$index"
                create(-1,id)
                bot.state.sending("training:-1:$id",-1,-1,null)
                bot.state.deliveryResult("training:-1:$id",status)
            }
        }
        fun restart() { bot=SelfServiceBot(fake,Database(bot.service.database.path),fake.bot) }
        fun assertBlockedHistoryRetained(status:String) {
            bot.service.database.read { c ->
                assertEquals(20,sqlQuery(c,"SELECT COUNT(*) FROM actions WHERE group_id=-1 AND training_id LIKE 'old-%' AND delivered_at IS NULL") { it.getInt(1) }.single())
                assertEquals(20,sqlQuery(c,"SELECT COUNT(*) FROM bot_deliveries WHERE delivery_key LIKE 'training:-1:old-%' AND status=? AND message_id IS NULL",status) { it.getInt(1) }.single())
            }
        }
    }

    @ParameterizedTest @ValueSource(strings=["FAILED","UNKNOWN"])
    fun `twenty terminal cards do not hide the next ready card after restart`(status:String) {
        val f=Fixture(dir.resolve("same-group-$status.sqlite"))
        f.blockedCards(status)
        f.create(-1,"new")
        f.restart()
        assertEquals(listOf(-1L to "new"),f.bot.state.pendingCards().map { it.first to it.second })
        assertEquals(1,f.bot.nextPollTimeout(25))
        f.bot.maintain()
        val delivery=assertNotNull(f.bot.state.delivery("training:-1:new"))
        assertEquals("SENT",delivery.status)
        assertNotNull(delivery.message)
        assertEquals(1,f.fake.sent.size)
        assertTrue(f.bot.state.pendingCards().isEmpty())
        f.assertBlockedHistoryRetained(status)
    }

    @ParameterizedTest @ValueSource(strings=["FAILED","UNKNOWN"])
    fun `terminal cards from one group do not block another group with the same training identifier`(status:String) {
        val f=Fixture(dir.resolve("other-group-$status.sqlite"))
        f.blockedCards(status)
        f.create(-2,"old-0")
        assertEquals(listOf(-2L to "old-0"),f.bot.state.pendingCards().map { it.first to it.second })
        f.bot.maintain()
        val delivered=assertNotNull(f.bot.state.delivery("training:-2:old-0"))
        assertEquals("SENT",delivered.status)
        assertTrue(f.fake.messages.containsKey(-2L to requireNotNull(delivered.message)))
        assertTrue(f.fake.sent.all { it.chat.id==-2L })
        f.assertBlockedHistoryRetained(status)
    }

    @Test fun `unknown delivery with a known message retries its edit instead of sending another card`() {
        val f=Fixture(dir.resolve("unknown-edit.sqlite"))
        f.create(-1,"existing")
        val old=f.fake.send(-1,"Previous card")
        f.bot.state.sending("training:-1:existing",-1,-1,null)
        f.bot.state.deliveryResult("training:-1:existing","UNKNOWN",old.id)
        assertEquals(listOf(-1L to "existing"),f.bot.state.pendingCards().map { it.first to it.second })
        f.bot.maintain()
        assertEquals("SENT",f.bot.state.delivery("training:-1:existing")?.status)
        assertEquals(old.id,f.bot.state.delivery("training:-1:existing")?.message)
        assertEquals(1,f.fake.sent.size)
        assertTrue(f.fake.messages.getValue(-1L to old.id).text!!.contains("Synthetic training"))
        assertTrue(f.bot.state.pendingCards().isEmpty())
    }
}
