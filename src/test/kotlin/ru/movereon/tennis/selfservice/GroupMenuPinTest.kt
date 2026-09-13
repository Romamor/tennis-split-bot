package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.telegram.*
import java.nio.file.Path
import kotlin.test.*

class GroupMenuPinTest {
    @TempDir lateinit var dir:Path
    private val api=FakeTelegramApi()
    private lateinit var bot:SelfServiceBot
    private var seq=1L
    private fun setup() {
        bot=SelfServiceBot(api,Database(dir.resolve("bot.sqlite")),api.bot)
        for(group in listOf(-1L,-2L)) {
            api.members[group to 1L]=TgMember("administrator",user=TgUser(1,firstName="Игрок"))
            api.members[group to api.bot.id]=TgMember("administrator",user=api.bot)
        }
    }
    private fun start(group:Long=-1,text:String="/start"):TgUpdate =
        TgUpdate(seq++,TgMessage(seq,TgChat(group,"supergroup","Группа"),TgUser(1,firstName="Игрок"),text)).also(bot::handle)
    private fun key(group:Long=-1)="group-menu:$group"
    private fun menu(group:Long=-1)=requireNotNull(bot.state.delivery(key(group))?.message)
    private fun run(command:SettlementCommand)=bot.service.execute(Access(-1,1,true),"test:${seq++}",command)

    @Test fun `start and menu pin the same message silently and replay creates no duplicate`() {
        setup();start();val id=menu();bot.maintain()
        assertEquals(listOf(-1L to id),api.silentPins)
        assertEquals("SENT",bot.state.pinStatus(key()))
        // Reopening explicitly also restores a menu that a person unpinned manually.
        api.pinned.clear()
        val event=start(text="/menu@tennis_test_bot");bot.maintain()
        assertEquals(id,menu());assertEquals(1,api.sent.count { it.chat.id==-1L })
        assertEquals(listOf(-1L to id),api.pinned)
        val attempts=api.pinAttempts;bot.handle(event);bot.maintain()
        assertEquals(attempts,api.pinAttempts)
    }

    @Test fun `training accounting and cancellation never unpin group menus or another group`() {
        setup();start();start(-2);bot.maintain()
        val menus=listOf(-1L to menu(),-2L to menu(-2))
        for((id,cancel) in listOf("finished" to false,"cancelled" to true)) {
            run(SettlementCommand.CreateTraining(id,"Теннис","2026-09-14","18:30"))
            run(SettlementCommand.AddPlayers(id,1,listOf(1)))
            run(SettlementCommand.ChangeAttendance(id,1,AttendanceChange.ADJUST_MINUTES,60))
            run(SettlementCommand.ChangeAttendance(id,1,AttendanceChange.SET_PAID,100))
            bot.maintain()
            val trainingMessage=bot.state.delivery("training:-1:$id")!!.message!!
            val version=bot.service.training(Access(-1,1,true),id).version
            if(cancel) run(SettlementCommand.CancelTraining(id,version)) else run(SettlementCommand.FinishTraining(id,version))
            bot.maintain()
            assertTrue(api.unpinned.contains(-1L to trainingMessage))
            assertTrue(menus.none { it in api.unpinned })
            assertEquals("SENT",bot.state.pinStatus(key()));assertEquals("SENT",bot.state.pinStatus(key(-2)))
        }
    }

    @Test fun `existing unpinned menus are picked up after upgrade without republishing`() {
        setup();start();val id=menu();bot.state.pinStatus(key(),"NONE")
        bot=SelfServiceBot(api,bot.service.database,api.bot);bot.maintain();bot.maintain()
        assertEquals(listOf(-1L to id),api.pinned);assertEquals(1,api.sent.size)
        bot=SelfServiceBot(api,bot.service.database,api.bot);bot.maintain()
        assertEquals(1,api.pinAttempts)
    }

    @Test fun `uncertain and interrupted silent menu pins resume after restart`() {
        setup();start();val id=menu();api.pinFailure=TelegramFailure(FailureKind.UNCERTAIN)
        assertFailsWith<TelegramFailure> { bot.maintain() }
        assertEquals("PENDING",bot.state.pinStatus(key()))
        bot.state.pinStatus(key(),"SENDING")
        bot=SelfServiceBot(api,bot.service.database,api.bot)
        assertEquals("PENDING",bot.state.pinStatus(key()))
        api.pinFailure=null;bot.maintain();bot.maintain()
        assertEquals(listOf(-1L to id),api.silentPins);assertEquals(1,api.sent.size)
    }

    @Test fun `permission rejection waits for an explicit command then retries the existing menu`() {
        setup();start();val id=menu();api.pinFailure=TelegramFailure(FailureKind.REJECTED,403)
        bot.maintain();assertEquals("FAILED",bot.state.pinStatus(key()))
        bot.maintain();assertEquals(1,api.pinAttempts)
        api.pinFailure=null;start(text="/start");bot.maintain()
        assertEquals(id,menu());assertEquals(listOf(-1L to id),api.silentPins);assertEquals(1,api.sent.size)
    }

    @Test fun `deleted menu is replaced and pinned while uncertain delivery is not duplicated`() {
        setup();start();val old=menu();bot.maintain();api.messages.remove(-1L to old)
        start(text="/menu");bot.maintain()
        assertNotEquals(old,menu());assertEquals(-1L to menu(),api.silentPins.last())
        api.acceptThenFail={chat,_ -> chat==-2L};start(-2)
        assertNull(bot.state.delivery(key(-2))?.message)
        val attempts=api.pinAttempts;start(-2);bot.maintain()
        assertEquals(attempts,api.pinAttempts);assertEquals(1,api.sent.count { it.chat.id==-2L })
    }
}
