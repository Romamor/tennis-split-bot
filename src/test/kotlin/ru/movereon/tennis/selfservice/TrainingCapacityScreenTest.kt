package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import java.nio.file.Path
import kotlin.test.*

class TrainingCapacityScreenTest {
    @TempDir lateinit var dir:Path
    private val creator=Access(-1,1,true)
    private lateinit var service:SettlementService
    private lateinit var polls:TrainingPolls
    private lateinit var state:InteractionStore
    private lateinit var screens:Screens
    private fun setup():TrainingPoll {
        service=SettlementService(Database(dir.resolve("capacity-screen.sqlite")))
        service.register(SettlementGroup(-1,"Группа","Europe/Moscow"))
        service.database.write { c -> (1L..51L).forEach { user ->
            sqlUpdate(c,"INSERT INTO users(id,first_name) VALUES(?,?)",user,"Игрок $user")
            sqlUpdate(c,"INSERT INTO group_users(group_id,user_id,present) VALUES(-1,?,1)",user)
        } }
        polls=TrainingPolls(service);polls.setEnabled(creator,true)
        val p=polls.create(creator,"poll","Сбор","2026-11-06","18:30","Не приду")
        polls.attach(p,"telegram-poll",1)
        state=InteractionStore(service.database)
        screens=Screens(service,state,"example_tennis_bot")
        return polls.get(-1,p.id)
    }
    private fun render(kind:String,inGroup:Boolean=false)=screens.render(ScreenAction(kind,-1,"poll"),creator,"capacity-screen",1,inGroup=inGroup)
    private fun actions(output:Screens.Output)=output.keyboard.rows.flatten().mapNotNull { button ->
        button.callbackData?.removePrefix("n:")?.let { state.button(it)?.action?.kind }
    }

    @Test fun `stopped poll exceeding capacity explains retained answers without offering recovery again`() {
        val p=setup()
        (1L..50L).forEach { polls.vote(it,p,it,0) }
        polls.beginClose(creator,p.id);polls.vote(51,p,51,0);polls.stopped(p)
        assertFalse(polls.finish(p))
        for(kind in listOf("poll_detail","poll_close_confirm","poll_close_recover_confirm","poll_discard_confirm")) {
            for(inGroup in listOf(false,true)) {
                val output=render(kind,inGroup)
                assertContains(output.text,"Голосование остановлено")
                assertContains(output.text,"Записались: 51")
                assertContains(output.text,"50 участников, включая гостей")
                assertContains(output.text,"Тренировка не создана; все ответы сохранены")
                assertContains(output.text,"Создай отдельные тренировки")
                assertTrue(actions(output).all { it in setOf("poll_list","menu","close_panel") })
                assertFalse(output.keyboard.rows.flatten().any { it.text.contains("Создать тренировку") || it.text.contains("Повторить") })
            }
        }
        assertEquals(51,polls.count(p));assertEquals("FAILED",polls.get(-1,p.id).status)
        assertFalse(polls.hasClosingPolls());assertEquals(0,service.trainings(creator).total)
    }

    @Test fun `open oversized poll allows votes to be withdrawn before completion is offered again`() {
        val p=setup();(1L..51L).forEach { polls.vote(it,p,it,0) }
        for(kind in listOf("poll_detail","poll_close_confirm")) {
            val output=render(kind)
            assertContains(output.text,"Сбор остаётся открытым")
            assertContains(output.text,"Записались: 51")
            assertContains(output.text,"отозвать свой голос")
            assertFalse(actions(output).any { it in setOf("poll_close","poll_close_confirm") })
        }
        polls.vote(100,p,51,null)
        val ready=render("poll_detail")
        assertContains(ready.text,"Записались: 50")
        assertContains(actions(ready),"poll_close_confirm")
        assertEquals("OPEN",polls.get(-1,p.id).status)
    }

    @Test fun `stopped transport failure within capacity retains explicit recovery`() {
        val p=setup();polls.vote(1,p,1,0);polls.beginClose(creator,p.id);polls.closeFailed(p,true)
        assertContains(actions(render("poll_detail")),"poll_close_recover_confirm")
        val confirmation=render("poll_close_recover_confirm")
        assertContains(actions(confirmation),"poll_close_recover")
        assertContains(confirmation.text,"Сейчас записались: 1")
    }

    @Test fun `fifty long names and large valid values fit in one complete rich card`() {
        val minutes=Long.MAX_VALUE/50/30*30
        val players=(1L..50L).map { Attendance(it,true,minutes,paid=Long.MAX_VALUE/50,ordinal=(it-1).toInt()) }
        val training=TrainingRecord(-1,"full","\"<&>".repeat(25),"2026-11-06","18:30",TrainingPhase.OPEN,1,0,1,players)
        val accounts=players.associate { it.userId to Account(it.userId,"Имя ${it.userId} "+"\"<&>".repeat(40),username="player_${it.userId}") }
        val card=TrainingCard.render(training,accounts::getValue)
        assertEquals(50,training.participantCount)
        assertEquals(50,Regex("<tr><td>").findAll(card.html).count())
        assertTrue(card.text.length<=32768);assertTrue(card.html.length<=32768)
        accounts.forEach { (id,account) ->
            assertContains(card.text,Screens.clean(account.name,48))
            assertContains(card.html,"href=\"https://t.me/player_$id\"")
        }
        assertContains(card.html,"&quot;&lt;&amp;&gt;")
        assertTrue(card.text.substringAfterLast('\n').startsWith("Час:"))
    }

    @Test fun `one long name with forty nine guests keeps all fifty rows within the rich card limit`() {
        val minutes=Long.MAX_VALUE/50/30*30
        val training=TrainingRecord(-1,"guests","\"<&>".repeat(25),"2026-11-06","18:30",TrainingPhase.OPEN,1,0,1,
            listOf(Attendance(1,true,minutes,minutes,Long.MAX_VALUE,guestCount=49)))
        val card=TrainingCard.render(training) { Account(it,"\"<&>".repeat(40)) }
        assertEquals(50,training.participantCount)
        assertEquals(50,Regex("<tr><td>").findAll(card.html).count())
        assertTrue(card.text.length<=32768);assertTrue(card.html.length<=32768)
        (1..49).forEach { assertContains(card.html,"гость $it</a>") }
    }
}
