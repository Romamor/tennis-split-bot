package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class UnpinRecoveryTest {
    @TempDir lateinit var dir:Path
    private class TestClock:Clock() {
        var now=Instant.parse("2026-11-06T12:00:00Z")
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
    }
    @Test fun `unpin network failure preserves retry and never starves new cards`() {
        val api=FakeTelegramApi();val clock=TestClock()
        var bot=SelfServiceBot(api,Database(dir.resolve("unpin.sqlite")),api.bot,clock)
        bot.service.remember(Account(1,"Игрок"))
        bot.service.register(SettlementGroup(-1,"Группа","Europe/Moscow"));bot.service.rememberMembership(-1,1,true)
        val access=Access(-1,1,true)
        fun create(id:String)=bot.service.execute(access,"create:$id",SettlementCommand.CreateTraining(id,"Теннис","2026-11-06","18:30"))
        create("old")
        bot.service.execute(access,"add",SettlementCommand.AddPlayers("old",1,listOf(1)))
        bot.service.execute(access,"minutes",SettlementCommand.ChangeAttendance("old",1,AttendanceChange.ADJUST_MINUTES,60))
        bot.service.execute(access,"paid",SettlementCommand.ChangeAttendance("old",1,AttendanceChange.SET_PAID,100))
        bot.maintain()
        bot.service.execute(access,"close",SettlementCommand.FinishTraining("old",bot.service.training(access,"old").version))
        api.unpinFailure=TelegramFailure(FailureKind.UNCERTAIN)
        create("fresh");bot.maintain()
        assertEquals("SENT",bot.state.delivery("training:-1:fresh")?.status)
        assertEquals("UNPIN_PENDING",bot.state.pinStatus("training:-1:old"))
        val attempts=api.unpinAttempts.size
        bot.maintain();assertEquals(attempts,api.unpinAttempts.size)
        clock.now=clock.now.plusSeconds(5);api.unpinFailure=null;bot.maintain()
        assertEquals(attempts+1,api.unpinAttempts.size)
        assertEquals("UNPINNED",bot.state.pinStatus("training:-1:old"))
        bot=SelfServiceBot(api,bot.service.database,api.bot,clock);bot.maintain()
        assertEquals(attempts+1,api.unpinAttempts.size)
    }
}
