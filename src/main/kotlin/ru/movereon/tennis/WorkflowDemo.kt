package ru.movereon.tennis

import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Explicit local fixtures: no real Telegram identity or membership is asserted. */
fun runWorkflowDemo(path: Path) {
    val clock = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC)
    val store = SqliteAccountingStore(path, clock)
    val service = GroupService(store, clock)
    val organizer = VerifiedGroupMember("workflow-demo", 1, true)
    val boris = VerifiedGroupMember("workflow-demo", 2)
    val sasha = VerifiedGroupMember("workflow-demo", 3)
    fun act(id: String, command: WorkflowCommand, member: VerifiedGroupMember = organizer) = service.execute(member, id, command)

    act("group", WorkflowCommand.CreateGroup("Europe/Moscow"))
    act("andrey", WorkflowCommand.AddParticipant("andrey", "Андрей"))
    act("boris", WorkflowCommand.AddParticipant("boris", "Борис"))
    act("sasha", WorkflowCommand.AddParticipant("sasha", "Саша"))
    act("link-andrey", WorkflowCommand.LinkSelf("andrey", 1))
    act("link-boris", WorkflowCommand.LinkSelf("boris", 1), boris)
    act("create", WorkflowCommand.CreateDraft("training", "2026-09-08"))
    val content = DraftContent("2026-09-08", listOf(PlayerInput("andrey", 120), PlayerInput("boris", 120), PlayerInput("sasha", 120)),
        listOf(PaymentInput("andrey", 350), PaymentInput("boris", 400))).withPlusOne("boris")
    act("fill", WorkflowCommand.SaveDraft("training", 1, content), boris)
    act("post", WorkflowCommand.PostDraft("training", 2))
    act("link-sasha", WorkflowCommand.LinkSelf("sasha", 1), sasha)
    act("edit", WorkflowCommand.SaveDraft("training", 3,
        content.copy(payments = listOf(PaymentInput("andrey", 500), PaymentInput("boris", 400)))), boris)
    act("post-edit", WorkflowCommand.PostDraft("training", 4))
    act("return", WorkflowCommand.RecordTransfer("return", "sasha", "andrey", 100, "2026-09-08"), sasha)
    act("inactive", WorkflowCommand.SetActive("sasha", 2, false))

    println("Локальная демонстрация участников и черновиков: ${store.path}")
    println("Подключения к Telegram нет; личности демонстрационные.")
    println("Команд в истории: ${service.audit(organizer).size}; финансовых операций: ${store.history(organizer.groupId).size}")
    service.participants(organizer, includeInactive = true).forEach {
        println("${it.participant.name}: ${it.balance} ₽${if (it.participant.active) "" else " · больше не ходит"}")
    }
    val draft = service.draft(organizer, "training")
    println("Тренировка: ${draft.status}; версия записи ${draft.version}, версия расчёта ${draft.financialVersion}")
    println("Проверка базы: ${store.verifyIntegrity()}")
}
