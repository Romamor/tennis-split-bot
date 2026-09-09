package ru.movereon.tennis

import ru.movereon.tennis.application.*
import ru.movereon.tennis.storage.Database
import java.nio.file.Files
import java.nio.file.Path

private fun existing(path: String): Database {
    require(Files.isRegularFile(Path.of(path))) { "Файл базы не найден" }
    return Database(Path.of(path))
}

/** Maintenance commands never contact Telegram. Demonstrations use a separate database file. */
fun main(args: Array<String>) {
    when {
        args.contentEquals(arrayOf("bot")) -> ru.movereon.tennis.telegram.runBot()
        args.size == 2 && args[0] == "verify" -> println(existing(args[1]).verify())
        args.size == 3 && args[0] == "backup" -> println("Резервная копия проверена: ${existing(args[1]).backup(Path.of(args[2]))}")
        args.size == 2 && args[0] == "demo" -> {
            val path = Path.of(args[1])
            require(!Files.exists(path)) { "Для демонстрации укажи новый файл" }
            val s = SettlementService(Database(path))
            val a = Access(-1, 1, true)
            s.register(SettlementGroup(-1, "Демонстрация", "Europe/Moscow"))
            for ((id, name) in listOf(1L to "Андрей", 2L to "Борис", 3L to "Вера", 4L to "Саша")) {
                s.remember(Account(id, name)); s.rememberMembership(-1, id, true)
            }
            s.execute(a, "create", SettlementCommand.CreateTraining("training", "Теннис", "2026-09-09", "19:00"))
            for (id in 1L..4L) s.execute(a, "join:$id", SettlementCommand.ChangeAttendance("training", id, AttendanceChange.JOIN))
            s.execute(a, "paid:1", SettlementCommand.ChangeAttendance("training", 1, AttendanceChange.SET_PAID, 350))
            s.execute(a, "paid:2", SettlementCommand.ChangeAttendance("training", 2, AttendanceChange.SET_PAID, 400))
            s.execute(a, "finish", SettlementCommand.FinishTraining("training", s.training(a, "training").version))
            s.roster(a).items.forEach { println("${it.account.name}: ${it.balance} ₽") }
            println(s.database.verify())
        }
        else -> println("Использование: bot | demo <новый файл> | verify <база> | backup <база> <новая копия>")
    }
}
