package ru.movereon.tennis

import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.OperationDetails
import ru.movereon.tennis.storage.SqliteAccountingStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

private data class DemoStep(val id: String, val title: String, val actor: Actor, val command: Command)
private val andrey = ParticipantId("andrey")
private val boris = ParticipantId("boris")
private val vera = ParticipantId("vera")
private val sasha = ParticipantId("sasha")
private val names = mapOf(andrey to "Андрей", boris to "Борис", vera to "Вера", sasha to "Саша")

private fun demoSteps(): List<DemoStep> {
    val author = Actor("demo:andrey", andrey)
    val payer = Actor("demo:vera", vera)
    return listOf(
        DemoStep("t1", "Первая тренировка: Вера с +1, общие расходы 750 ₽", author,
            Command.PostTraining("training-1", Training(
                listOf(PlayerSlot(andrey, 120), PlayerSlot(boris, 120), PlayerSlot(vera, 120), PlayerSlot(vera, 120, true)),
                listOf(ExpensePayment(andrey, 350), ExpensePayment(boris, 400))))),
        DemoStep("advance", "Вера перевела Борису 500 ₽, включая аванс", payer, Command.RecordTransfer("advance", vera, boris, 500)),
        DemoStep("t2", "Вторая тренировка: разное время, расходы 900 ₽", author,
            Command.PostTraining("training-2", Training(
                listOf(PlayerSlot(andrey, 60), PlayerSlot(boris, 120), PlayerSlot(vera, 120), PlayerSlot(sasha, 60)),
                listOf(ExpensePayment(boris, 900))))),
        DemoStep("t3", "Третья тренировка: Андрей и Саша оплатили по 300 ₽", author,
            Command.PostTraining("training-3", Training(names.keys.map { PlayerSlot(it, 60) },
                listOf(ExpensePayment(andrey, 300), ExpensePayment(sasha, 300))))),
        DemoStep("partial", "Вера отметила перевод Андрею 100 ₽", payer, Command.RecordTransfer("partial", vera, andrey, 100)),
        DemoStep("review", "Андрей уточняет поступление: перевод пока не учитывается", author,
            Command.ChangeTransfer("partial", 1, TransferAction.REVIEW)),
        DemoStep("confirm", "Андрей подтвердил поступление 100 ₽", author,
            Command.ChangeTransfer("partial", 2, TransferAction.CONFIRM)),
    )
}

private fun showBalances(balances: Map<ParticipantId, Long>) {
    names.forEach { (id, name) -> println("$name: ${balances.getOrDefault(id, 0)} ₽") }
}

private fun showPlan(balances: Map<ParticipantId, Long>) {
    println("\nПредложение рассчитаться:")
    suggestTransfers(balances).forEach { println("${names[it.from]} → ${names[it.to]}: ${it.amount} ₽") }
}

private fun existingStore(path: String): SqliteAccountingStore {
    require(Files.isRegularFile(Path.of(path))) { "Файл базы не найден: $path" }
    return SqliteAccountingStore(Path.of(path))
}

/** Local demonstrations and database maintenance. No Telegram connection is made. */
fun main(args: Array<String>) {
    when {
        args.contentEquals(arrayOf("bot")) -> ru.movereon.tennis.telegram.runBot()
        args.size == 2 && args[0] == "workflow-demo" -> runWorkflowDemo(Path.of(args[1]))
        args.isEmpty() -> {
            val book = AccountBook("demo")
            demoSteps().forEach { step ->
                book.execute(step.id, step.actor, step.command)
                println("\n${step.title}")
                showBalances(book.balances())
            }
            showPlan(book.balances())
        }
        args.size == 2 && args[0] == "storage-demo" -> {
            val store = SqliteAccountingStore(Path.of(args[1]))
            val before = store.history("demo").size
            demoSteps().forEach { step ->
                store.execute("demo", step.id, step.actor, step.command,
                    OperationDetails(LocalDate.of(2026, 9, 8), "Демонстрационные данные"))
            }
            val history = store.history("demo")
            println("База демонстрации: ${store.path}")
            println("Новых операций: ${history.size - before}; всего: ${history.size}")
            showBalances(store.balances("demo"))
            showPlan(store.balances("demo"))
            println("Проверка целостности: ${store.verifyIntegrity()}")
        }
        args.size == 2 && args[0] == "verify" -> {
            val store = existingStore(args[1])
            println("Проверка ${store.path}: ${store.verifyIntegrity()}")
        }
        args.size == 3 && args[0] == "backup" -> {
            val backup = existingStore(args[1]).backup(Path.of(args[2]))
            println("Резервная копия создана и проверена: $backup")
        }
        else -> error("Использование: [bot | storage-demo <база> | workflow-demo <база> | verify <база> | backup <база> <новая копия>]")
    }
}
