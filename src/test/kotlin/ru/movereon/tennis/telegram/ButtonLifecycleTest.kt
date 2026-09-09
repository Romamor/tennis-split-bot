package ru.movereon.tennis.telegram

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ButtonLifecycleTest {
    @TempDir lateinit var directory: Path
    private val initial=Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"),ZoneOffset.UTC)
    private lateinit var db: SqliteAccountingStore
    private lateinit var state: TelegramStore
    private val member=VerifiedGroupMember("group",1,true)
    private fun count(table:String) = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getInt(1) } }
    }
    private fun day(value:Long) = TelegramStore(db,Clock.offset(initial,Duration.ofDays(value)))

    @BeforeEach fun setup() {
        db=SqliteAccountingStore(directory.resolve("test.sqlite"),initial)
        state=TelegramStore(db,initial)
        GroupService(db,initial).execute(member,"create",WorkflowCommand.CreateGroup("UTC"))
        state.register(BotGroup("group",-100,"Group"))
    }

    @Test fun `unchanged screens reuse tokens within one user and group`() {
        val keyboard=listOf(BotAction("menu"),BotAction("balances"),BotAction("history"))
        val first=state.actions(1,"group",keyboard)
        repeat(100) { assertEquals(first,state.actions(1,"group",keyboard)) }
        assertEquals(3,count("tg_actions"))
        assertNotEquals(first,state.actions(2,"group",keyboard))
        GroupService(db,initial).execute(member.copy(groupId="other"),"create",WorkflowCommand.CreateGroup("UTC"))
        state.register(BotGroup("other",-101,"Other"))
        assertNotEquals(first,state.actions(1,"other",keyboard))
        assertNotEquals(first[1],state.action(1,"group",BotAction("balances",page=1)))
        assertEquals(10,count("tg_actions"))
    }

    @Test fun `new creation and recovery intents always have fresh tokens`() {
        listOf(BotAction("create_draft",field="2026-09-09"),BotAction("new_transfer"),
            BotAction("ask",field="name"),BotAction("recover",entity="menu:group")).forEach {
            assertNotEquals(state.action(1,"group",it),state.action(1,"group",it),it.kind)
        }
    }

    @Test fun `showing the same button extends its lifetime`() {
        val action=BotAction("menu")
        val token=state.action(1,"group",action)
        assertEquals(token,day(6).action(1,"group",action))
        assertEquals(0,day(8).pruneExpiredActions())
        assertNotNull(day(8).action(token))
        assertEquals(1,day(13).pruneExpiredActions())
        assertNull(day(13).action(token))
    }

    @Test fun `cleanup keeps pending prompts and unfinished plans until they finish`() {
        val prompt=state.action(1,"group",BotAction("ask",field="name"))
        state.saveSession(BotSession(1,"group",10,PendingInput(prompt,BotAction("ask",field="name"),11)))
        val unfinished=state.action(2,"group",BotAction("commit_editor",entity="draft",editorId="editor",editorVersion=1))
        state.savePlan(20,SavedPlan(2,"group",unfinished,BotAction("commit_editor")))
        val finished=state.action(3,"group",BotAction("menu"))
        state.savePlan(21,SavedPlan(3,"group",finished,BotAction("menu")))
        state.complete(21)
        val link=state.link("group",BotAction("menu"))
        val atEight=day(8)
        assertEquals(1,atEight.pruneExpiredActions())
        assertNotNull(state.action(prompt)); assertNotNull(state.action(unfinished)); assertNull(state.action(finished))
        assertNotNull(state.link(link))
        state.saveSession(state.session(1).copy(input=null))
        state.complete(20)
        assertEquals(2,atEight.pruneExpiredActions())
        assertEquals(0,count("tg_actions"))
        assertNotNull(state.plan(20)); assertNotNull(state.plan(21))
        assertEquals(22L,state.offset())
        assertEquals(2,count("tg_updates"))
    }

    @Test fun `bounded cleanup removes a backlog without deleting financial records`() {
        val service=GroupService(db,initial)
        service.execute(member,"participant",WorkflowCommand.AddParticipant("a","A"))
        service.execute(member,"training",WorkflowCommand.CommitDraft("t",0,DraftContent("2026-09-09",
            listOf(PlayerInput("a",60)),listOf(PaymentInput("a",100))),true))
        val financialBefore=listOf("operations","balance_entries","workflow_audit","workflow_commands","outbox").associateWith(::count)
        state.actions(1,"group",(0..1004).map { BotAction("balances",page=it) })
        assertEquals(1000,day(8).pruneExpiredActions())
        assertEquals(5,count("tg_actions"))
        assertEquals(5,day(8).pruneExpiredActions())
        assertEquals(financialBefore,financialBefore.keys.associateWith(::count))
        db.verifyIntegrity()
    }

    @Test fun `version four migration preserves old tokens and grants a full grace period`() {
        val path=directory.resolve("v4.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { s ->
            listOf("001_accounting","002_workflow","003_telegram","004_editors").forEach { resource ->
                val sql=requireNotNull(javaClass.getResourceAsStream("/db/$resource.sql")).bufferedReader().use { it.readText() }
                sql.split(';').filter { it.isNotBlank() }.forEach { s.execute(it) }
            }
            s.execute("PRAGMA application_id=${0x54534e53}")
            s.execute("PRAGMA user_version=4")
            s.execute("INSERT INTO app_groups VALUES('old','UTC',1)")
            s.execute("INSERT INTO tg_groups VALUES('old',-100,'Old')")
            s.execute("INSERT INTO tg_actions VALUES('legacy',1,'old','{\"kind\":\"menu\"}')")
        } }
        val migrated=SqliteAccountingStore(path,initial)
        assertEquals("menu",TelegramStore(migrated,initial).action("legacy")!!.action.kind)
        assertEquals(0,TelegramStore(migrated,Clock.offset(initial,Duration.ofDays(6))).pruneExpiredActions())
        // Reopening must not renew the migration grace period.
        val reopened=SqliteAccountingStore(path,Clock.offset(initial,Duration.ofDays(8)))
        val expired=TelegramStore(reopened,Clock.offset(initial,Duration.ofDays(8)))
        assertEquals(1,expired.pruneExpiredActions())
        migrated.verifyIntegrity()
    }
}
