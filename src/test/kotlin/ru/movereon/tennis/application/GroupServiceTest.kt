package ru.movereon.tennis.application

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class GroupServiceTest {
    @TempDir lateinit var directory: Path
    private lateinit var store: SqliteAccountingStore
    private lateinit var service: GroupService
    private val clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC)
    private val root = VerifiedGroupMember("group", 1, true)
    private val boris = VerifiedGroupMember("group", 2)
    private val vera = VerifiedGroupMember("group", 3)
    private val sasha = VerifiedGroupMember("group", 4)
    private var commandNumber = 0
    private val today = "2026-09-08"
    private fun path() = directory.resolve("workflow.sqlite")
    private fun reopened() = GroupService(SqliteAccountingStore(path(), clock), clock)
    private fun act(command: WorkflowCommand, actor: VerifiedGroupMember = root, key: String = "command-${++commandNumber}") = service.execute(actor, key, command)
    private fun profile(id: String) = service.participants(root, true).single { it.participant.id == id }
    private fun content(cost: Long = 100, date: String = today) = DraftContent(date,
        listOf(PlayerInput("a", 60), PlayerInput("b", 60)), listOf(PaymentInput("a", cost)))
    private fun ready(id: String = "training", creator: VerifiedGroupMember = root, input: DraftContent = content()) {
        act(WorkflowCommand.CreateDraft(id, input.date), creator)
        act(WorkflowCommand.SaveDraft(id, 1, input), creator)
    }
    private fun sql(text: String) = DriverManager.getConnection("jdbc:sqlite:${path()}").use { connection ->
        connection.createStatement().use { it.execute(text) }
    }
    private fun fails(code: ErrorCode, block: () -> Unit) = assertEquals(code, assertFailsWith<AccountingException>(block = block).code)

    @BeforeEach fun setup() {
        store = SqliteAccountingStore(path(), clock)
        service = GroupService(store, clock)
        act(WorkflowCommand.CreateGroup("Europe/Moscow"))
        listOf("a" to "Андрей", "b" to "Борис", "v" to "Вера", "s" to "Саша").forEach { (id, name) -> act(WorkflowCommand.AddParticipant(id, name)) }
        act(WorkflowCommand.LinkSelf("a", 1))
        act(WorkflowCommand.LinkSelf("b", 1), boris)
        act(WorkflowCommand.LinkSelf("v", 1), vera)
    }

    @Test fun `initialization requires a verified administrator and a valid time zone`() {
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.CreateGroup("UTC"), VerifiedGroupMember("other", 9)) }
        fails(ErrorCode.INVALID_INPUT) { act(WorkflowCommand.CreateGroup("Not/AZone"), VerifiedGroupMember("other", 9, true)) }
        fails(ErrorCode.INVALID_STATE) { service.participants(VerifiedGroupMember("other", 9)) }
        fails(ErrorCode.INVALID_STATE) { act(WorkflowCommand.CreateGroup("UTC")) }
    }

    @Test fun `participant and plus-one posting matches the agreed calculation`() {
        val input = DraftContent(today,
            listOf(PlayerInput("a", 120), PlayerInput("b", 120), PlayerInput("v", 120)),
            listOf(PaymentInput("a", 350), PaymentInput("b", 400))).withPlusOne("v")
        assertEquals(120L, input.players.last().minutes)
        ready(input = input)
        val receipt = act(WorkflowCommand.PostDraft("training", 2))
        assertEquals(1L, receipt.financialVersion)
        assertEquals(mapOf("a" to 162L, "b" to 212L, "v" to -374L, "s" to 0L),
            service.participants(root, true).associate { it.participant.id to it.balance })
        assertEquals(4, service.participants(root).size)
        assertEquals(DraftStatus.POSTED, reopened().draft(root, "training").status)
        assertEquals(IntegrityReport(1, 1), store.verifyIntegrity())
    }

    @Test fun `a manually recorded participant links without changing past balances`() {
        ready(input = DraftContent(today, listOf(PlayerInput("s", 60), PlayerInput("a", 60)), listOf(PaymentInput("a", 300))))
        act(WorkflowCommand.PostDraft("training", 2))
        val before = store.history(root.groupId)
        val balance = profile("s").balance
        act(WorkflowCommand.LinkSelf("s", 1), sasha)
        assertEquals(-150L, balance)
        assertEquals(balance, profile("s").balance)
        assertEquals(4L, profile("s").participant.telegramUserId)
        assertEquals(before, store.history(root.groupId))
        assertEquals(4, reopened().participants(sasha).size)
        assertTrue(service.audit(root).last().beforeJson!!.contains("\"telegramUserId\":null"))
    }

    @Test fun `duplicate names are allowed but links remain unique`() {
        act(WorkflowCommand.AddParticipant("s2", "Саша"), boris)
        assertEquals(2, service.participants(root).count { it.participant.name == "Саша" })
        fails(ErrorCode.INVALID_STATE) { act(WorkflowCommand.LinkSelf("s", 1)) }
        fails(ErrorCode.INVALID_STATE) { act(WorkflowCommand.LinkSelf("a", 2), sasha) }
        fails(ErrorCode.INVALID_STATE) { act(WorkflowCommand.CorrectLink("s", 1, 2)) }
        assertEquals(null, profile("s").participant.telegramUserId)
        assertEquals(1L, profile("s").participant.version)
    }

    @Test fun `two users cannot concurrently claim one profile`() {
        val services = listOf(reopened(), reopened())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll((0..1).map { i -> Callable {
                try { services[i].execute(VerifiedGroupMember("group", 4L + i), "claim-$i", WorkflowCommand.LinkSelf("s", 1)); null }
                catch (failure: AccountingException) { failure.code }
            } }).map { it.get() }
            assertEquals(1, outcomes.count { it == null })
            assertEquals(1, outcomes.count { it == ErrorCode.STALE_VERSION })
            assertTrue(profile("s").participant.telegramUserId in setOf(4L, 5L))
        } finally { pool.shutdownNow() }
    }

    @Test fun `only the organizer corrects links and only own profile can be renamed by a member`() {
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.CorrectLink("s", 1, 4), boris) }
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.RenameParticipant("a", 2, "Другое имя"), boris) }
        act(WorkflowCommand.RenameParticipant("b", 2, "Боря"), boris)
        act(WorkflowCommand.CorrectLink("b", 3, 5))
        assertEquals("Боря", profile("b").participant.name)
        assertEquals(5L, profile("b").participant.telegramUserId)
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.RecordTransfer("pay", "a", "b", 100, today), boris) }
        act(WorkflowCommand.RecordTransfer("pay", "a", "b", 100, today), VerifiedGroupMember("group", 5))
        assertEquals(-100L, profile("b").balance)
    }

    @Test fun `inactive participant stays in balances and settlement without losing membership rights`() {
        ready(input = DraftContent(today, listOf(PlayerInput("v", 60), PlayerInput("a", 60)), listOf(PaymentInput("a", 100))))
        act(WorkflowCommand.PostDraft("training", 2))
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.SetActive("v", 2, false), boris) }
        act(WorkflowCommand.SetActive("v", 2, false))
        assertFalse(service.participants(root).any { it.participant.id == "v" })
        assertEquals(-50L, profile("v").balance)
        assertEquals(listOf(SuggestedTransfer(ParticipantId("v"), ParticipantId("a"), 50)), service.settlementPlan(root))
        act(WorkflowCommand.CreateDraft("next", today), vera)
        assertEquals(3L, service.draft(vera, "next").createdBy)
    }

    @Test fun `an organizer may delegate the role to a linked participant`() {
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.AppointOrganizer(2), vera) }
        act(WorkflowCommand.AppointOrganizer(2))
        ready(creator = vera)
        act(WorkflowCommand.PostDraft("training", 2), boris)
        assertEquals(DraftStatus.POSTED, service.draft(root, "training").status)
    }

    @Test fun `members edit shared drafts while posting belongs to creator or organizer`() {
        act(WorkflowCommand.CreateDraft("training", today))
        act(WorkflowCommand.SaveDraft("training", 1, content()), boris)
        assertEquals(content(), reopened().draft(vera, "training").content)
        assertEquals(emptyMap(), store.balances(root.groupId))
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.PostDraft("training", 2), vera) }
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.CancelDraft("training", 2), boris) }
        act(WorkflowCommand.PostDraft("training", 2))
        assertEquals(50L, profile("a").balance)
    }

    @Test fun `concurrent draft edits reject the stale version`() {
        ready()
        val services = listOf(reopened(), reopened())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll((0..1).map { i -> Callable {
                try { services[i].execute(boris, "edit-$i", WorkflowCommand.SaveDraft("training", 2, content(200L + i * 100))); null }
                catch (failure: AccountingException) { failure.code }
            } }).map { it.get() }
            assertEquals(1, outcomes.count { it == null })
            assertEquals(1, outcomes.count { it == ErrorCode.STALE_VERSION })
            assertTrue(service.draft(root, "training").content.payments.single().amount in setOf(200L, 300L))
            assertEquals(emptyMap(), store.balances(root.groupId))
        } finally { pool.shutdownNow() }
    }

    @Test fun `working edits leave old balances active and can be discarded or posted`() {
        ready()
        act(WorkflowCommand.PostDraft("training", 2))
        act(WorkflowCommand.SaveDraft("training", 3, content(200)), boris)
        assertEquals(DraftStatus.EDITING, service.draft(root, "training").status)
        assertEquals(50L, profile("a").balance)
        act(WorkflowCommand.DiscardChanges("training", 4))
        assertEquals(content(), service.draft(root, "training").content)
        assertEquals(1, store.history(root.groupId).size)
        act(WorkflowCommand.SaveDraft("training", 5, content(300)), boris)
        act(WorkflowCommand.PostDraft("training", 6))
        assertEquals(150L, profile("a").balance)
        assertEquals(2L, service.draft(root, "training").financialVersion)
    }

    @Test fun `cancellation preserves real returns and cannot be repeated`() {
        ready()
        act(WorkflowCommand.PostDraft("training", 2))
        act(WorkflowCommand.RecordTransfer("return", "b", "a", 10, today), boris)
        act(WorkflowCommand.CancelDraft("training", 3))
        assertEquals(-10L, profile("a").balance)
        assertEquals(10L, profile("b").balance)
        assertEquals(TransferStatus.ACTIVE, store.transfer(root.groupId, "return")?.status)
        fails(ErrorCode.INVALID_STATE) { act(WorkflowCommand.CancelDraft("training", 4)) }
        fails(ErrorCode.INVALID_STATE) { act(WorkflowCommand.SaveDraft("training", 4, content())) }
    }

    @Test fun `cancelling an incomplete draft makes no financial entry`() {
        act(WorkflowCommand.CreateDraft("draft", today))
        act(WorkflowCommand.CancelDraft("draft", 1))
        assertEquals(emptyList(), store.history(root.groupId))
        assertEquals(emptyList(), store.pendingEvents())
        assertEquals(DraftStatus.CANCELLED, service.draft(root, "draft").status)
        assertEquals(emptyList(), service.drafts(root))
    }

    @Test fun `future and incomplete drafts are saved but cannot be posted`() {
        act(WorkflowCommand.CreateDraft("incomplete", today))
        act(WorkflowCommand.SaveDraft("incomplete", 1, DraftContent(today, listOf(PlayerInput("a", 60)))))
        fails(ErrorCode.INVALID_INPUT) { act(WorkflowCommand.PostDraft("incomplete", 2)) }
        ready("future", input = content(date = "2026-09-09"))
        fails(ErrorCode.INVALID_INPUT) { act(WorkflowCommand.PostDraft("future", 2)) }
        assertEquals(2, service.drafts(root).size)
        assertEquals(emptyMap(), store.balances(root.groupId))
    }

    @Test fun `posting uses the group local date rather than UTC`() {
        val early = Clock.fixed(Instant.parse("2026-09-07T22:30:00Z"), ZoneOffset.UTC)
        ready()
        GroupService(store, early).execute(root, "local-midnight", WorkflowCommand.PostDraft("training", 2))
        assertEquals(50L, profile("a").balance)
    }

    @Test fun `draft validation checks every supplied value and participant group`() {
        act(WorkflowCommand.CreateDraft("draft", today))
        val wrong = listOf(
            content().copy(players = listOf(PlayerInput("unknown", 60))),
            content().copy(players = listOf(PlayerInput("a", 0))),
            content().copy(players = listOf(PlayerInput("a", 60, true))),
            content().copy(payments = listOf(PaymentInput("a", 100), PaymentInput("a", 100))),
            content().copy(date = "08/09/2026"),
        )
        wrong.forEach { value -> fails(ErrorCode.INVALID_INPUT) { act(WorkflowCommand.SaveDraft("draft", 1, value)) } }
        assertEquals(1L, service.draft(root, "draft").version)
    }

    @Test fun `existing rounding order survives edits and plus-one time is independent`() {
        val input = DraftContent(today, listOf(PlayerInput("a", 120), PlayerInput("b", 120), PlayerInput("v", 120)),
            listOf(PaymentInput("a", 350), PaymentInput("b", 400))).withPlusOne("v")
        ready(input = input)
        act(WorkflowCommand.SaveDraft("training", 2, input.copy(players = input.players.reversed())), boris)
        assertEquals(input.players, service.draft(root, "training").content.players)
        act(WorkflowCommand.PostDraft("training", 3))
        assertEquals(-374L, profile("v").balance)
        val shorter = input.copy(players = input.players.map { if (it.plusOne) it.copy(minutes = 60) else it })
        act(WorkflowCommand.SaveDraft("training", 4, shorter))
        assertEquals(120L, service.draft(root, "training").content.players.single { it.participantId == "v" && !it.plusOne }.minutes)
        assertEquals(60L, service.draft(root, "training").content.players.single { it.plusOne }.minutes)
    }

    @Test fun `either transfer party may record and only the review initiator may resolve`() {
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.RecordTransfer("pay", "a", "b", 100, today), vera) }
        act(WorkflowCommand.RecordTransfer("pay", "a", "b", 100, today), boris)
        act(WorkflowCommand.ChangeTransfer("pay", 1, TransferIntent.REVIEW))
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.ChangeTransfer("pay", 2, TransferIntent.CONFIRM), boris) }
        act(WorkflowCommand.ChangeTransfer("pay", 2, TransferIntent.CONFIRM))
        assertEquals(100L, profile("a").balance)
        assertEquals(3, store.history(root.groupId).size)
    }

    @Test fun `independently recorded similar transfers require explicit confirmation`() {
        act(WorkflowCommand.RecordTransfer("pay", "a", "b", 100, today))
        val second = WorkflowCommand.RecordTransfer("second", "a", "b", 100, today)
        val warning = assertFailsWith<SimilarTransferFound> { act(second, boris, "second") }
        assertEquals(listOf("pay"), warning.transferIds)
        act(second.copy(allowSimilar = true), boris, "second")
        assertEquals(200L, profile("a").balance)
    }

    @Test fun `representation requires organizer rights and unlinked or verified absent party`() {
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.RecordTransfer("pay", "s", "b", 100, today, onBehalfOf = "s"), vera) }
        act(WorkflowCommand.RecordTransfer("pay", "s", "b", 100, today, onBehalfOf = "s"))
        fails(ErrorCode.FORBIDDEN) { act(WorkflowCommand.RecordTransfer("left", "b", "v", 100, today, onBehalfOf = "b")) }
        service.execute(root, "left", WorkflowCommand.RecordTransfer("left", "b", "v", 100, today, onBehalfOf = "b"),
            VerifiedAbsentMember(root.groupId, boris.userId))
        assertEquals(100L, profile("s").balance)
        assertEquals(ParticipantId("b"), store.history(root.groupId).last().actor.party)
    }

    @Test fun `representative can resolve their own clarification after the participant links`() {
        act(WorkflowCommand.RecordTransfer("pay", "s", "b", 100, today, onBehalfOf = "s"))
        act(WorkflowCommand.ChangeTransfer("pay", 1, TransferIntent.REVIEW, onBehalfOf = "s"))
        act(WorkflowCommand.LinkSelf("s", 1), sasha)
        act(WorkflowCommand.ChangeTransfer("pay", 2, TransferIntent.CONFIRM, onBehalfOf = "s"))
        assertEquals(100L, profile("s").balance)
    }

    @Test fun `a final application failure rolls back draft accounting audit and outbox together`() {
        ready()
        val auditBefore = service.audit(root)
        sql("CREATE TRIGGER reject_receipt BEFORE INSERT ON workflow_commands WHEN NEW.command_id='fail-post' BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertFailsWith<SQLException> { act(WorkflowCommand.PostDraft("training", 2), key = "fail-post") }
        assertEquals(DraftStatus.DRAFT, reopened().draft(root, "training").status)
        assertEquals(2L, service.draft(root, "training").version)
        assertEquals(emptyMap(), store.balances(root.groupId))
        assertEquals(emptyList(), store.history(root.groupId))
        assertEquals(emptyList(), store.pendingEvents())
        assertEquals(auditBefore, service.audit(root))
        sql("DROP TRIGGER reject_receipt")
        val result = act(WorkflowCommand.PostDraft("training", 2), key = "fail-post")
        assertEquals(result, reopened().execute(root, "fail-post", WorkflowCommand.PostDraft("training", 2)))
        assertEquals(1, store.history(root.groupId).size)
        assertEquals(1, store.pendingEvents().size)
    }

    @Test fun `workflow retries survive restart and reject changed payload or actor`() {
        val input = WorkflowCommand.AddParticipant("extra", "Гость")
        val result = act(input, key = "retry")
        assertEquals(result, reopened().execute(root, "retry", input))
        fails(ErrorCode.COMMAND_CONFLICT) { reopened().execute(root, "retry", input.copy(name = "Другой")) }
        fails(ErrorCode.COMMAND_CONFLICT) { reopened().execute(boris, "retry", input) }
        assertEquals(1, service.participants(root).count { it.participant.id == "extra" })
    }

    @Test fun `backup includes profiles roles drafts and application audit`() {
        ready()
        act(WorkflowCommand.PostDraft("training", 2))
        act(WorkflowCommand.SaveDraft("training", 3, content(300)), boris)
        val backup = store.backup(directory.resolve("backup.sqlite"))
        val restored = GroupService(SqliteAccountingStore(backup, clock), clock)
        assertEquals(service.participants(root, true), restored.participants(root, true))
        assertEquals(service.draft(root, "training"), restored.draft(root, "training"))
        assertEquals(service.audit(root), restored.audit(root))
        restored.execute(root, "post-backup", WorkflowCommand.PostDraft("training", 4))
        assertEquals(150L, restored.participants(root).single { it.participant.id == "a" }.balance)
        assertEquals(50L, profile("a").balance)
    }
}
