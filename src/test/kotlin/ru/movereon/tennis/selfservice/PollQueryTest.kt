package ru.movereon.tennis.selfservice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.*
import java.nio.file.Path
import java.time.*
import kotlin.test.*

class PollQueryTest {
    @TempDir lateinit var dir:Path
    private val queries=mutableListOf<String>()
    private val clock=Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"),ZoneOffset.UTC)
    private fun setup():Pair<SettlementService,TrainingPolls> {
        val s=SettlementService(Database(dir.resolve("polls.sqlite"),queries::add),clock)
        for(u in 1L..40) s.remember(Account(u,"Игрок $u"))
        for(g in listOf(-1L,-2L)) {
            s.register(SettlementGroup(g,"Группа ${-g}","Europe/Moscow"))
            for(u in 1L..40) s.rememberMembership(g,u,true)
        }
        s.execute(Access(-1,1,true),"appoint",SettlementCommand.SetAdministrator(1,true))
        val polls=TrainingPolls(s,clock)
        for(g in listOf(-1L,-2L)) {
            polls.setEnabled(Access(g,1,true),true)
            for(i in 0..99) polls.create(Access(g,if(i%2==0) 2 else 3),"p%03d".format(i),"Теннис $i","2026-09-14","18:30","Не приду")
        }
        return s to polls
    }
    @Test fun `pagination respects author and group for every page and loads only one page`() {
        val (s,p)=setup()
        val creator=Access(-1,2)
        val all=(0..9).flatMap { page ->
            p.managedPage(creator,page).also { assertEquals(50,it.total);assertEquals(10,it.pages);assertEquals(5,it.items.size) }.items
        }
        assertEquals(50,all.map { it.id }.distinct().size)
        assertTrue(all.all { it.creator==2L && it.group==-1L })
        assertEquals(all.sortedBy { it.id },all)
        assertEquals(9,p.managedPage(creator,999).index)
        assertEquals(0,p.managedPage(creator,-1).index)
        val admin=Access(-1,1)
        assertEquals(100,p.managedPage(admin,0).total)
        // The appointment in group -1 grants no access to other authors in group -2.
        assertEquals(0,p.managedPage(Access(-2,1),0).total)
        s.rememberMembership(-1,2,false)
        assertFailsWith<IllegalArgumentException> { p.managedPage(creator,0) }
    }
    @Test fun `rendering a hundred poll list uses a bounded query budget and no roster scan`() {
        val (s,p)=setup()
        val state=InteractionStore(s.database,clock)
        val screens=Screens(s,state,"demo_tennis_bot")
        queries.clear();s.database.readTransactions.set(0)
        val output=screens.render(ScreenAction("poll_list",-1),Access(-1,1),"measure",1)
        assertTrue(output.text.contains("100"))
        assertEquals(5,output.keyboard.rows.flatten().count { it.text.contains("Теннис") })
        assertEquals(1,queries.count { it.contains("FROM group_admins") })
        assertTrue(queries.any { it.contains("LIMIT 5 OFFSET") })
        assertFalse(queries.any { it.contains("SELECT u.*") })
        assertTrue(s.database.readTransactions.get()<=3,"Opening connections must not scale with poll count")
        assertTrue(queries.size<=25,"SQL work must not include a role lookup per row")
        queries.clear()
        assertFalse(p.hasClosingPolls())
        assertTrue(queries.single().startsWith("SELECT EXISTS"))
        assertTrue(p.hasActive(-1,1,true));assertFalse(p.hasActive(-2,1,false))
    }
}
