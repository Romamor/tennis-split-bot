package ru.movereon.tennis.telegram

import ru.movereon.tennis.storage.SqliteAccountingStore
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneId

/** This class deliberately has no generated toString containing the secret. */
class BotConfig(val token: String, val database: Path, val timeZone: String, val pollTimeout: Int) {
    companion object {
        fun load(file: Path = Path.of(".env"), environment: Map<String,String> = System.getenv()): BotConfig {
            val settings = if(Files.exists(file)) Files.readAllLines(file).mapNotNull { line ->
                val text=line.trim()
                if(text.isEmpty() || text.startsWith('#')) null else {
                    require('=' in text) { "В .env ожидается ИМЯ=значение" }
                    val value=text.substringAfter('=').trim()
                    text.substringBefore('=').trim() to if(value.length >= 2 && value.first() == value.last() && value.first() in "\"'") value.substring(1,value.length-1) else value
                }
            }.toMap() else emptyMap()
            fun value(name:String,default:String="") = environment[name] ?: settings[name] ?: default
            val token=value("TELEGRAM_BOT_TOKEN")
            require(token.isNotBlank()) { "Вставь токен из @BotFather в TELEGRAM_BOT_TOKEN в локальном файле .env" }
            require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]{20,}"))) { "Проверь формат TELEGRAM_BOT_TOKEN в .env" }
            val zone=value("BOT_TIME_ZONE","Europe/Moscow")
            require(runCatching { ZoneId.of(zone) }.isSuccess) { "Проверь BOT_TIME_ZONE: ожидается название часового пояса" }
            val timeout=value("BOT_POLL_TIMEOUT","25").toIntOrNull()
            require(timeout != null && timeout in 1..50) { "BOT_POLL_TIMEOUT должен быть от 1 до 50" }
            return BotConfig(token,Path.of(value("BOT_DATABASE_PATH","data/bot.sqlite")),zone,timeout)
        }
    }
}

/** No webhook or public HTTP listener. A file lock prevents two local pollers sharing this database. */
fun runBot() {
    val config = BotConfig.load()
    val path=config.database.toAbsolutePath().normalize()
    Files.createDirectories(path.parent)
    FileChannel.open(path.resolveSibling("${path.fileName}.bot.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { channel ->
        val lock=channel.tryLock() ?: error("С этой базой уже работает другой процесс бота")
        lock.use {
            val api=HttpTelegramApi(config.token)
            val bot=TelegramBot(api,SqliteAccountingStore(path),api.me(),defaultZone=config.timeZone)
            println("Бот @${bot.identity.username} запущен. База: $path. Для остановки нажми Ctrl+C.")
            while(!Thread.currentThread().isInterrupted) {
                try {
                    bot.maintainButtons()
                    bot.delivery.flush()
                    api.updates(bot.state.offset(),config.pollTimeout).sortedBy { it.id }.forEach(bot::handle)
                } catch (failure: TelegramFailure) {
                    if(failure.code in setOf(401,409)) error("Telegram отклонил подключение. Проверь токен, отсутствие другого процесса и ранее установленного webhook.")
                    System.err.println("Telegram временно недоступен; повторим запрос. Токен и сообщения в журнал не записываются.")
                    try { Thread.sleep((failure.retryAfter ?: 3).coerceIn(1,60)*1000L) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
        }
    }
}
