package ru.movereon.tennis.storage

import ru.movereon.tennis.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant

data class StoredOperation(val actor: Actor, val command: Command, val details: OperationDetails,
    val receipt: Receipt, val event: JournalEvent, val recordedAt: Instant)
data class PendingEvent(val groupId: String, val sequence: Long, val kind: String, val entityId: String, val recordedAt: Instant)
data class IntegrityReport(val groupCount: Int, val operationCount: Int)
class StorageCorruption(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Every command uses a fresh connection and an IMMEDIATE transaction. No financial
 * state is cached between calls, so separate store instances see committed writes.
 * The application layer must authorize actors before calling this store.
 */
class SqliteAccountingStore(path: Path, private val clock: Clock = Clock.systemUTC()) {
    val path: Path = path.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.path.parent)
        initialize()
    }

    fun execute(groupId: String, commandId: String, actor: Actor, command: Command,
        details: OperationDetails = OperationDetails()): Receipt = writeTransaction { connection ->
        executeInTransaction(connection, groupId, commandId, actor, command, details)
    }

    internal fun <T> writeTransaction(body: (Connection) -> T): T = connection().use { connection ->
        transaction(connection, immediate = true) { body(connection) }
    }

    internal fun <T> readTransaction(body: (Connection) -> T): T = connection().use { connection ->
        transaction(connection, immediate = false) { body(connection) }
    }

    internal fun balancesInTransaction(connection: Connection, groupId: String): Map<ParticipantId, Long> =
        readCheckpoint(connection, groupId)?.let { AccountBook.fromCheckpoint(it).balances() } ?: emptyMap()

    internal fun transferInTransaction(connection: Connection, groupId: String, id: String): TransferSnapshot? =
        readCheckpoint(connection, groupId)?.let { AccountBook.fromCheckpoint(it).transfer(id) }

    internal fun executeInTransaction(connection: Connection, groupId: String, commandId: String, actor: Actor,
        command: Command, details: OperationDetails = OperationDetails()): Receipt {
        checkId(groupId)
        checkId(commandId)
        // Encoding makes a private immutable copy before any stateful operation.
        val payload = StorageCodec.encodeCommand(command)
        val metadata = StorageCodec.encodeDetails(details)
        val frozenCommand = StorageCodec.decodeCommand(payload)
            connection.prepareStatement("SELECT actor_id, represented_party, command_json, details_json, sequence, entity_version FROM operations WHERE group_id=? AND command_id=?").use { statement ->
                statement.setString(1, groupId); statement.setString(2, commandId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        checkAccounting(rows.getString("actor_id") == actor.id && rows.getString("represented_party") == actor.party?.value &&
                            rows.getString("command_json") == payload && rows.getString("details_json") == metadata,
                            ErrorCode.COMMAND_CONFLICT, "Command ID already used for different input")
                        return Receipt(rows.getLong("sequence"), rows.getLong("entity_version"))
                    }
                }
            }
            val state = readCheckpoint(connection, groupId)
            val book = state?.let(AccountBook::fromCheckpoint) ?: AccountBook(groupId)
            val receipt = book.execute(commandId, actor, frozenCommand)
            val event = book.journal().single()
            val checkpoint = StorageCodec.encodeCheckpoint(book.checkpoint())
            connection.prepareStatement("INSERT INTO group_state(group_id,revision,checkpoint_json) VALUES(?,?,?) ON CONFLICT(group_id) DO UPDATE SET revision=excluded.revision,checkpoint_json=excluded.checkpoint_json").use { statement ->
                statement.setString(1, groupId); statement.setLong(2, receipt.sequence); statement.setString(3, checkpoint)
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO operations(group_id,sequence,command_id,actor_id,represented_party,entity_id,kind,entity_version,command_json,details_json,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                statement.setString(1, groupId); statement.setLong(2, receipt.sequence); statement.setString(3, commandId)
                statement.setString(4, actor.id); statement.setString(5, actor.party?.value); statement.setString(6, command.entityId)
                statement.setString(7, event.kind); statement.setLong(8, receipt.entityVersion); statement.setString(9, payload)
                statement.setString(10, metadata); statement.setString(11, clock.instant().toString())
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO balance_entries(group_id,sequence,entry_index,participant_id,amount) VALUES(?,?,?,?,?)").use { statement ->
                event.entries.forEachIndexed { index, entry ->
                    statement.setString(1, groupId); statement.setLong(2, receipt.sequence); statement.setInt(3, index)
                    statement.setString(4, entry.participant.value); statement.setLong(5, entry.amount); statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement("INSERT INTO outbox(group_id,sequence) VALUES(?,?)").use { statement ->
                statement.setString(1, groupId); statement.setLong(2, receipt.sequence); statement.executeUpdate()
            }
            return receipt
    }

    fun balances(groupId: String): Map<ParticipantId, Long> = connection().use { connection ->
        checkId(groupId)
        readCheckpoint(connection, groupId)?.let { AccountBook.fromCheckpoint(it).balances() } ?: emptyMap()
    }

    fun transfer(groupId: String, transferId: String): TransferSnapshot? = connection().use { connection ->
        checkId(groupId); checkId(transferId)
        readCheckpoint(connection, groupId)?.let { AccountBook.fromCheckpoint(it).transfer(transferId) }
    }

    fun history(groupId: String): List<StoredOperation> = connection().use { connection ->
        checkId(groupId)
        transaction(connection, immediate = false) { readHistory(connection, groupId) }
    }

    /** Pending domain events, not pre-rendered Telegram messages. Delivery is a later layer. */
    fun pendingEvents(limit: Int = 100, groupId: String? = null): List<PendingEvent> {
        require(limit in 1..1000)
        return connection().use { connection ->
            connection.prepareStatement("SELECT o.group_id,o.sequence,o.kind,o.entity_id,o.recorded_at FROM outbox q JOIN operations o USING(group_id,sequence) WHERE q.acknowledged_at IS NULL AND (? IS NULL OR o.group_id=?) ORDER BY o.group_id,o.sequence LIMIT ?").use { statement ->
                statement.setString(1, groupId); statement.setString(2, groupId); statement.setInt(3, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PendingEvent(rows.getString("group_id"), rows.getLong("sequence"),
                        rows.getString("kind"), rows.getString("entity_id"), Instant.parse(rows.getString("recorded_at"))))
                } }
            }
        }
    }

    /** Mark only after successful processing; a repeated acknowledgement is harmless. */
    fun acknowledge(groupId: String, sequence: Long): Boolean {
        checkId(groupId)
        require(sequence > 0)
        return connection().use { connection ->
            connection.prepareStatement("UPDATE outbox SET acknowledged_at=? WHERE group_id=? AND sequence=? AND acknowledged_at IS NULL").use { statement ->
                statement.setString(1, clock.instant().toString()); statement.setString(2, groupId); statement.setLong(3, sequence)
                statement.executeUpdate() == 1
            }
        }
    }

    /** Consistent SQLite backup, including committed WAL data. Never overwrites a file. */
    fun backup(destination: Path): Path {
        val target = destination.toAbsolutePath().normalize()
        require(!Files.exists(target)) { "Backup destination already exists" }
        Files.createDirectories(target.parent)
        // Reserve the path exclusively; SQLite accepts an existing empty output file.
        Files.createFile(target)
        connection().use { connection ->
            connection.prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, target.toString()); statement.executeUpdate()
            }
        }
        SqliteAccountingStore(target, clock).verifyIntegrity()
        return target
    }

    /** Checks SQLite integrity, persisted journal totals and the current financial state. */
    fun verifyIntegrity(): IntegrityReport = connection().use { connection -> transaction(connection, immediate = false) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check").use { rows ->
                ensure(rows.next() && rows.getString(1) == "ok" && !rows.next(), "SQLite integrity check failed")
            }
            statement.executeQuery("PRAGMA foreign_key_check").use { rows -> ensure(!rows.next(), "Broken foreign key") }
        }
        val groups = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT group_id FROM group_state ORDER BY group_id").use { rows -> buildList {
                while (rows.next()) add(rows.getString(1))
            } }
        }
        var operationCount = 0
        groups.forEach { group ->
            val state = requireNotNull(readCheckpoint(connection, group))
            val current = AccountBook.fromCheckpoint(state).balances()
            val operations = readHistory(connection, group)
            ensure(operations.size.toLong() == state.sequence, "Journal and checkpoint versions differ")
            var balances = emptyMap<ParticipantId, Long>()
            operations.forEachIndexed { index, operation ->
                ensure(operation.receipt.sequence == index.toLong() + 1, "Gap in journal sequence")
                balances = applyEntries(balances, operation.event.entries)
            }
            ensure(balances == current, "Journal and checkpoint balances differ")
            val outboxCount = connection.prepareStatement("SELECT COUNT(*) FROM outbox WHERE group_id=?").use { statement ->
                statement.setString(1, group)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            ensure(outboxCount == state.sequence, "Missing notification event")
            operationCount += operations.size
        }
        IntegrityReport(groups.size, operationCount)
    } }

    private fun readCheckpoint(connection: Connection, groupId: String): BookCheckpoint? =
        connection.prepareStatement("SELECT revision,checkpoint_json FROM group_state WHERE group_id=?").use { statement ->
            statement.setString(1, groupId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else {
                    val state = decode { StorageCodec.decodeCheckpoint(rows.getString("checkpoint_json")) }
                    ensure(state.groupId == groupId && state.sequence == rows.getLong("revision"), "Checkpoint identity or revision mismatch")
                    state
                }
            }
        }

    private fun readHistory(connection: Connection, groupId: String): List<StoredOperation> {
        val entries = mutableMapOf<Long, MutableList<BalanceEntry>>()
        connection.prepareStatement("SELECT sequence,entry_index,participant_id,amount FROM balance_entries WHERE group_id=? ORDER BY sequence,entry_index").use { statement ->
            statement.setString(1, groupId)
            statement.executeQuery().use { rows -> while (rows.next()) {
                val list = entries.getOrPut(rows.getLong("sequence")) { mutableListOf() }
                ensure(rows.getInt("entry_index") == list.size, "Gap in balance entries")
                list += BalanceEntry(ParticipantId(rows.getString("participant_id")), rows.getLong("amount"))
            } }
        }
        return connection.prepareStatement("SELECT * FROM operations WHERE group_id=? ORDER BY sequence").use { statement ->
            statement.setString(1, groupId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(decode {
                    val sequence = rows.getLong("sequence")
                    val actor = Actor(rows.getString("actor_id"), rows.getString("represented_party")?.let(::ParticipantId))
                    val command = StorageCodec.decodeCommand(rows.getString("command_json"))
                    ensure(command.entityId == rows.getString("entity_id"), "Command and journal entity differ")
                    StoredOperation(actor, command, StorageCodec.decodeDetails(rows.getString("details_json")),
                        Receipt(sequence, rows.getLong("entity_version")),
                        JournalEvent(sequence, rows.getString("command_id"), actor.id, actor.party, command.entityId,
                            rows.getString("kind"), entries[sequence]?.toList() ?: emptyList()),
                        Instant.parse(rows.getString("recorded_at")))
                })
            } }
        }
    }

    private fun initialize() = connection().use { connection ->
        transaction(connection, immediate = true) {
            val version = connection.pragmaInt("user_version")
            val application = connection.pragmaInt("application_id")
            when (version) {
                0 -> {
                    val empty = connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").use { rows -> rows.next(); rows.getInt(1) == 0 }
                    }
                    require(application == 0 && empty) { "Refusing to initialize a different database" }
                    val sql = requireNotNull(javaClass.getResourceAsStream("/db/001_accounting.sql")).bufferedReader().use { it.readText() }
                    connection.createStatement().use { statement ->
                        sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                        statement.execute("PRAGMA application_id=$APPLICATION_ID")
                        statement.execute("PRAGMA user_version=1")
                    }
                }
                1, 2, 3, 4, 5, 6 -> require(application == APPLICATION_ID) { "Database belongs to another application" }
                else -> error("Unsupported database schema version: $version")
            }
            if (version < 2) {
                val sql = requireNotNull(javaClass.getResourceAsStream("/db/002_workflow.sql")).bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version=2")
                }
            }
            if (version < 3) {
                val sql = requireNotNull(javaClass.getResourceAsStream("/db/003_telegram.sql")).bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version=3")
                }
            }
            if (version < 4) {
                val sql = requireNotNull(javaClass.getResourceAsStream("/db/004_editors.sql")).bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version=4")
                }
            }
            if (version < 5) {
                val sql = requireNotNull(javaClass.getResourceAsStream("/db/005_button_lifecycle.sql")).bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    // Legacy buttons have no last-shown timestamp; grant a full grace period.
                    statement.executeUpdate("UPDATE tg_actions SET expires_at=${clock.instant().epochSecond + 7 * 24 * 60 * 60}")
                    statement.execute("PRAGMA user_version=5")
                }
            }
            if (version < 6) {
                val sql = requireNotNull(javaClass.getResourceAsStream("/db/006_active_buttons.sql")).bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    // Untracked controls expire normally; /menu creates a tracked panel.
                    statement.executeUpdate("UPDATE tg_actions SET expires_at=${clock.instant().epochSecond + 120}")
                    statement.execute("PRAGMA user_version=6")
                }
            }
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA journal_mode=WAL").use { rows -> ensure(rows.next() && rows.getString(1).equals("wal", true), "WAL mode is unavailable") }
        }
    }

    private fun connection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$path")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA synchronous=FULL")
            }
            return connection
        } catch (failure: Throwable) { connection.close(); throw failure }
    }

    private fun <T> transaction(connection: Connection, immediate: Boolean, body: () -> T): T {
        connection.createStatement().use { it.execute(if (immediate) "BEGIN IMMEDIATE" else "BEGIN") }
        try {
            val result = body()
            connection.createStatement().use { it.execute("COMMIT") }
            return result
        } catch (failure: Throwable) {
            try { connection.createStatement().use { it.execute("ROLLBACK") } } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun Connection.pragmaInt(name: String): Int = createStatement().use { statement ->
        statement.executeQuery("PRAGMA $name").use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun ensure(condition: Boolean, message: String) { if (!condition) throw StorageCorruption(message) }
    private fun <T> decode(body: () -> T): T = try { body() } catch (failure: RuntimeException) {
        if (failure is StorageCorruption) throw failure
        throw StorageCorruption("Invalid persisted accounting data", failure)
    }

    private companion object { const val APPLICATION_ID = 0x54534E53 }
}
