package ru.movereon.tennis.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.selfservice.*
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import kotlin.test.*

class UpdateInboxMigrationTest {
    @TempDir lateinit var dir:Path
    @Test fun `version nine receipt migration preserves money and retries legacy plans locally`() {
        val path=dir.resolve("migration.sqlite")
        val service=SettlementService(Database(path))
        for(u in 1L..2L) service.remember(Account(u,"Игрок $u"))
        service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        for(u in 1L..2L) service.rememberMembership(-1,u,true)
        service.execute(Access(-1,1,true),"transfer",SettlementCommand.RecordTransfer("transfer",1,2,300,"2026-11-06"))
        val state=InteractionStore(service.database)
        state.plan(100,EventPlan(1,1,ScreenAction("settings",0)))
        state.complete(101)
        val balances=service.database.balances(-1)
        service.database.write { c ->
            sqlUpdate(c,"ALTER TABLE bot_deliveries DROP COLUMN retry_at")
            sqlUpdate(c,"ALTER TABLE bot_deliveries DROP COLUMN pin_retry_at")
            sqlUpdate(c,"DROP INDEX pending_events_order");sqlUpdate(c,"DROP INDEX pending_events_due")
            sqlUpdate(c,"DROP TABLE bot_polling")
            for(column in listOf("update_json","ordering_key","received_order","next_attempt_at"))
                sqlUpdate(c,"ALTER TABLE bot_events DROP COLUMN $column")
            c.createStatement().use { it.execute("PRAGMA user_version=9") }
        }
        repeat(2) {
            val db=Database(path)
            assertEquals(balances,db.balances(-1))
            assertTrue(db.verify().contains("Схема 11"))
            assertEquals(102L,UpdateInbox(db).offset())
            assertNotNull(InteractionStore(db).plan(100))
            assertTrue(db.read { c -> sqlQuery(c,"PRAGMA foreign_key_check") { it.getString(1) }.isEmpty() })
        }
        val api=FakeTelegramApi();api.members[-1L to 1L]=TgMember("member")
        val bot=SelfServiceBot(api,Database(path),api.bot)
        bot.processPending()
        assertTrue(bot.state.completed(100))
        assertEquals("Игрок 1",bot.service.account(1).firstName)
        assertEquals(balances,bot.service.database.balances(-1))
        assertEquals(102L,bot.inbox.offset())
    }
}
