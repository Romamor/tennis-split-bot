package ru.movereon.tennis.telegram

import ru.movereon.tennis.storage.Database
import ru.movereon.tennis.selfservice.SelfServiceBot
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneId

/** This class deliberately has no generated toString containing the secret. */
class BotConfig(val token: String, val database: Path, val timeZone: String, val pollTimeout: Int, val uiDiagnostics:Boolean=false, val uiMetricsPath:Path?=null) {
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
            val diagnostics=value("BOT_UI_DIAGNOSTICS","false").toBooleanStrictOrNull()
            require(diagnostics!=null) { "BOT_UI_DIAGNOSTICS должен быть true или false" }
            return BotConfig(token,Path.of(value("BOT_DATABASE_PATH","data/settlements.sqlite")),zone,timeout,diagnostics,value("BOT_UI_METRICS_PATH").takeIf { it.isNotBlank() }?.let(Path::of))
        }
    }
}

internal fun awaitBotIdentity(api:TelegramApi,pause:(Long)->Unit={ Thread.sleep(it) }):TgUser {
    while(true) {
        try { return api.me() }
        catch(f:TelegramFailure) {
            if(f.kind !in setOf(FailureKind.UNCERTAIN,FailureKind.RETRY_LATER) || f.code in setOf(401,409)) throw f
            System.err.println("Telegram временно недоступен при запуске; повторим getMe без перезапуска JVM.")
            pause(RetryPolicy.delayMillis(f,3))
        }
    }
}

/** A batch is acknowledged only after receipt is durable; local retries cannot pin polling. */
internal fun runQueuedBotCycle(receive:()->List<TgUpdate>?,accept:(List<TgUpdate>)->Unit,
    process:()->Unit,finish:()->Unit,maintain:()->Unit) {
    // null means no Telegram response: in particular, it must not mark closing polls as drained.
    receive()?.let(accept)
    process()
    finish()
    maintain()
}

/** The persisted wall-clock deadline survives restart; already saved work remains runnable. */
internal class UpdatePollingSchedule(private val loadDeadline:()->Long={ 0L },private val saveDeadline:(Long)->Unit={},
    private val nowMillis:()->Long=System::currentTimeMillis) {
    private var nextAttemptAt=loadDeadline()
    private fun deadline(value:Long) {
        if(value==nextAttemptAt) return
        saveDeadline(value)
        nextAttemptAt=value
    }
    fun receive(request:()->List<TgUpdate>):List<TgUpdate>? {
        if(nextAttemptAt>nowMillis()) return null
        return try { request().also { deadline(0) } }
        catch(f:TelegramFailure) {
            if(f.backgroundDeferred || f.code in setOf(401,409)) throw f
            deadline(RetryPolicy.deadline(nowMillis(),f,3))
            System.err.println("Telegram временно недоступен; повторим получение обновлений. Сохранённые задания продолжают выполняться.")
            null
        }
    }
    /** Recheck local work once a second; this does not shorten the polling retry deadline. */
    fun waitMillis():Long=(nextAttemptAt-nowMillis()).coerceIn(0L,1000L)
}

/** No webhook or public HTTP listener. A file lock prevents two local pollers sharing this database. */
fun runBot() {
    val config = BotConfig.load()
    val path=config.database.toAbsolutePath().normalize()
    Files.createDirectories(path.parent)
    FileChannel.open(path.resolveSibling("${path.fileName}.bot.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { channel ->
        val lock=channel.tryLock() ?: error("С этой базой уже работает другой процесс бота")
        lock.use {
            val diagnostics=UiDiagnostics(config.uiDiagnostics)
            val metrics=UiMetrics(enabled=config.uiMetricsPath!=null)
            val api=HttpTelegramApi(config.token,diagnostics=diagnostics,metrics=metrics)
            val database=Database(path)
            database.withConnectionReuse {
                val bot=SelfServiceBot(api,database,awaitBotIdentity(api),zone=config.timeZone,diagnostics=diagnostics,metrics=metrics)
                println("Бот @${bot.identity.username} запущен. База: $path. Для остановки нажми Ctrl+C.")
                var maintenanceDeferred=false
                val polling=UpdatePollingSchedule(loadDeadline={ bot.inbox.pollRetryAt() },saveDeadline={ bot.inbox.pollRetryAt(it) })
                while(!Thread.currentThread().isInterrupted) {
                    try {
                        runQueuedBotCycle(
                            { polling.receive { api.updates(bot.inbox.offset(),bot.nextPollTimeout(if(maintenanceDeferred) 1 else config.pollTimeout)).also { maintenanceDeferred=false } } },
                            bot::acceptUpdates,{ bot.processPending() },bot::finishDrainedPolls,{ api.background(bot::maintain) })
                    } catch (failure: TelegramFailure) {
                        if(failure.backgroundDeferred) maintenanceDeferred=true
                        else {
                            if(failure.code in setOf(401,409)) error("Telegram отклонил подключение. Проверь токен, отсутствие другого процесса и ранее установленного webhook.")
                            System.err.println("Telegram временно недоступен; повторим запрос. Токен и сообщения в журнал не записываются.")
                            try { Thread.sleep(RetryPolicy.delayMillis(failure,3)) }
                            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        }
                    }
                    config.uiMetricsPath?.let { metrics.publish(it) }
                    val wait=polling.waitMillis()
                    if(wait>0) try { Thread.sleep(wait) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
        }
    }
}
