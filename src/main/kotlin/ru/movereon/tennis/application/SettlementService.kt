package ru.movereon.tennis.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The boundary supplies Access only after checking membership in the specified Telegram chat. */
class SettlementService(val database: Database, private val clock: Clock = Clock.systemUTC()) {
    private val json = Json { encodeDefaults = true }

    fun remember(account: Account) = database.write { c ->
        require(account.id > 0)
        sqlUpdate(c, """INSERT INTO users(id,first_name,last_name,username,is_bot) VALUES(?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET first_name=excluded.first_name,last_name=excluded.last_name,username=excluded.username""",
            account.id, account.firstName, account.lastName, account.username, account.isBot)
    }

    fun register(group: SettlementGroup) = database.write { c ->
        require(group.id < 0 && group.title.isNotBlank())
        ZoneId.of(group.timeZone)
        sqlUpdate(c, """INSERT INTO groups(id,title,time_zone) VALUES(?,?,?)
            ON CONFLICT(id) DO UPDATE SET title=excluded.title""", group.id, group.title, group.timeZone)
    }

    /** Called for identities actually received from Telegram, never an arbitrary name or username. */
    fun rememberMembership(groupId: Long, userId: Long, present: Boolean) = database.write { c ->
        sqlUpdate(c, """INSERT INTO group_users(group_id,user_id,present) VALUES(?,?,?)
            ON CONFLICT(group_id,user_id) DO UPDATE SET present=excluded.present""", groupId, userId, present)
    }

    fun account(id: Long): Account = database.read { account(it, id) }
    private fun account(c: Connection, id: Long): Account = sqlQuery(c, "SELECT * FROM users WHERE id=?", id, map = ::readAccount)
        .singleOrNull() ?: invalid("Аккаунт ещё не известен боту")
    private fun readAccount(r: ResultSet) = Account(r.getLong("id"), r.getString("first_name"), r.getString("last_name"), r.getString("username"), r.getBoolean("is_bot"))
    fun group(id: Long): SettlementGroup = database.read { c ->
        sqlQuery(c, "SELECT * FROM groups WHERE id=?", id) { SettlementGroup(it.getLong("id"), it.getString("title"), it.getString("time_zone")) }
            .singleOrNull() ?: invalid("Группа не найдена")
    }
    fun groups(userId: Long, page: Int = 0): Page<SettlementGroup> = database.read { c ->
        val condition = "FROM groups g JOIN group_users u ON u.group_id=g.id WHERE u.user_id=?"
        val total = count(c, "SELECT COUNT(*) $condition", userId)
        val index = pageIndex(page, total)
        Page(sqlQuery(c, "SELECT g.* $condition ORDER BY g.title,g.id LIMIT 8 OFFSET ?", userId, index * 8) {
            SettlementGroup(it.getLong("id"), it.getString("title"), it.getString("time_zone"))
        }, total, index)
    }
    fun isAdmin(access: Access): Boolean = database.read { admin(it, access) }
    private fun admin(c: Connection, a: Access) = a.telegramAdmin || count(c,
        "SELECT COUNT(*) FROM group_admins WHERE group_id=? AND user_id=?", a.groupId, a.userId) > 0
    private fun requireAdmin(c: Connection, a: Access) = allowed(admin(c, a), "Это действие доступно администратору этой группы")
    private fun known(c: Connection, group: Long, user: Long) = allowed(count(c,
        "SELECT COUNT(*) FROM group_users gu JOIN users u ON u.id=gu.user_id WHERE gu.group_id=? AND gu.user_id=? AND u.is_bot=0", group, user) == 1,
        "Аккаунт не найден в этой группе")

    fun execute(a: Access, requestId: String, command: SettlementCommand, absent: AbsentAccount? = null): ActionReceipt = database.write { c ->
        require(requestId.isNotBlank())
        known(c, a.groupId, a.userId)
        val payload = json.encodeToString(command)
        val previous = sqlQuery(c, "SELECT * FROM actions WHERE group_id=? AND request_id=?", a.groupId, requestId) {
            Triple(it.getLong("actor_id"), it.getString("payload_json"), ActionReceipt(it.getLong("id"), it.getLong("result_version")))
        }.singleOrNull()
        if (previous != null) {
            checkAccounting(previous.first == a.userId && previous.second == payload, ErrorCode.COMMAND_CONFLICT, "Этот запрос уже использован для другого действия")
            return@write previous.third
        }
        var trainingId: String? = null
        var transferId: String? = null
        var before: String? = null
        var entries = emptyList<BalanceEntry>()
        var version = 1L
        var after: String
        when (command) {
            is SettlementCommand.SetAdministrator -> {
                allowed(a.telegramAdmin, "Назначать администраторов могут только администраторы этой группы Telegram")
                known(c, a.groupId, command.userId)
                before = (count(c, "SELECT COUNT(*) FROM group_admins WHERE group_id=? AND user_id=?", a.groupId, command.userId) > 0).toString()
                if (command.enabled) sqlUpdate(c, """INSERT INTO group_admins(group_id,user_id,granted_by,granted_at) VALUES(?,?,?,?)
                    ON CONFLICT(group_id,user_id) DO NOTHING""", a.groupId, command.userId, a.userId, clock.instant().toString())
                else sqlUpdate(c, "DELETE FROM group_admins WHERE group_id=? AND user_id=?", a.groupId, command.userId)
                after = command.enabled.toString()
            }
            is SettlementCommand.RecordTransfer -> {
                transferId = command.id
                checkId(transferId)
                known(c, a.groupId, command.from); known(c, a.groupId, command.to)
                require(command.amount > 0 && command.from != command.to) { "Укажи положительную сумму и разных участников" }
                LocalDate.parse(command.date)
                require(command.note.length <= 300) { "Комментарий не длиннее 300 символов" }
                transferParty(c, a, setOf(command.from, command.to), command.onBehalfOf, absent)
                val similar = sqlQuery(c, """SELECT id FROM transfers WHERE group_id=? AND from_user=? AND to_user=?
                    AND amount=? AND occurred_on=? AND status<>'CANCELLED'""", a.groupId, command.from, command.to, command.amount, command.date) { it.getString(1) }
                if (similar.isNotEmpty() && !command.allowSimilar) throw DuplicateTransfer(similar)
                require(count(c, "SELECT COUNT(*) FROM transfers WHERE group_id=? AND id=?", a.groupId, transferId) == 0) { "Перевод уже записан" }
                sqlUpdate(c, """INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,note,status,version,created_by,created_at)
                    VALUES(?,?,?,?,?,?,?,'ACTIVE',1,?,?)""", a.groupId, transferId, command.from, command.to, command.amount, command.date, command.note, a.userId, clock.instant().toString())
                entries = transferEntries(transfer(c, a.groupId, transferId))
                after = json.encodeToString(transfer(c, a.groupId, transferId))
            }
            is SettlementCommand.ChangeTransfer -> {
                transferId = command.id
                val old = transfer(c, a.groupId, transferId)
                before = json.encodeToString(old)
                stale(old.version, command.version)
                val party = transferParty(c, a, setOf(old.from, old.to), command.onBehalfOf, absent)
                val status = when (command.change) {
                    TransferChange.REVIEW -> {
                        state(old.status == PaymentStatus.ACTIVE, "Перевод уже уточняется или отменён")
                        entries = transferEntries(old).map { it.copy(amount = -it.amount) }
                        PaymentStatus.REVIEW
                    }
                    TransferChange.CONFIRM, TransferChange.CANCEL -> {
                        state(old.status == PaymentStatus.REVIEW, "Сначала выбери «Уточнить перевод»")
                        allowed(old.reviewer == a.userId && old.reviewParty == party, "Завершить уточнение может тот, кто его начал")
                        if (command.change == TransferChange.CONFIRM) {
                            entries = transferEntries(old)
                            PaymentStatus.ACTIVE
                        } else PaymentStatus.CANCELLED
                    }
                }
                version = Math.addExact(old.version, 1)
                sqlUpdate(c, "UPDATE transfers SET status=?,version=?,reviewer=?,review_party=? WHERE group_id=? AND id=?", status.name, version,
                    a.userId.takeIf { status == PaymentStatus.REVIEW }, party.takeIf { status == PaymentStatus.REVIEW }, a.groupId, transferId)
                after = json.encodeToString(transfer(c, a.groupId, transferId))
            }
            else -> {
                if (command is SettlementCommand.CreateTraining) {
                    requireAdmin(c, a)
                    trainingId = command.id
                    checkId(trainingId)
                    validateDetails(command.title, command.date, command.startTime)
                    require(count(c, "SELECT COUNT(*) FROM trainings WHERE group_id=? AND id=?", a.groupId, trainingId) == 0) { "Тренировка уже создана" }
                    sqlUpdate(c, """INSERT INTO trainings(group_id,id,title,played_on,starts_at,status,version,created_by,created_at)
                        VALUES(?,?,?,?,?,'OPEN',1,?,?)""", a.groupId, trainingId, command.title, command.date, command.startTime, a.userId, clock.instant().toString())
                } else {
                    trainingId = when (command) {
                        is SettlementCommand.EditTraining -> command.id
                        is SettlementCommand.ChangeAttendance -> command.id
                        is SettlementCommand.FinishTraining -> command.id
                        is SettlementCommand.ReopenTraining -> command.id
                        is SettlementCommand.CancelTraining -> command.id
                        else -> error("Unhandled training command")
                    }
                    val old = training(c, a.groupId, trainingId)
                    before = json.encodeToString(old)
                    version = Math.addExact(old.version, 1)
                    if (command !is SettlementCommand.ChangeAttendance) requireAdmin(c, a)
                    when (command) {
                        is SettlementCommand.EditTraining -> {
                            stale(old.version, command.version); editable(old)
                            validateDetails(command.title, command.date, command.startTime)
                            sqlUpdate(c, "UPDATE trainings SET title=?,played_on=?,starts_at=? WHERE group_id=? AND id=?", command.title, command.date, command.startTime, a.groupId, trainingId)
                        }
                        is SettlementCommand.ChangeAttendance -> {
                            editable(old)
                            allowed(command.userId == a.userId || admin(c, a), "Можно менять только свои время и оплату")
                            known(c, a.groupId, command.userId)
                            val row = old.players.firstOrNull { it.userId == command.userId }
                                ?: Attendance(command.userId, false, ordinal = (old.players.maxOfOrNull { it.ordinal } ?: -1) + 1)
                            val changed = changeAttendance(row, command)
                            sqlUpdate(c, """INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,paid,ordinal)
                                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET
                                playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,paid=excluded.paid""",
                                a.groupId, trainingId, changed.userId, changed.playing, changed.minutes, changed.guestMinutes, changed.paid, changed.ordinal)
                            validateDraftTraining(training(c, a.groupId, trainingId).calculation())
                        }
                        is SettlementCommand.FinishTraining -> {
                            stale(old.version, command.version); editable(old)
                            val allocation = calculateTraining(old.calculation())
                            entries = reverseTraining(c, a.groupId, trainingId) + allocation.entries
                            sqlUpdate(c, "UPDATE trainings SET status='CLOSED',applied_version=? WHERE group_id=? AND id=?", version, a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE training_players SET applied_playing=playing WHERE group_id=? AND training_id=?", a.groupId, trainingId)
                        }
                        is SettlementCommand.ReopenTraining -> {
                            stale(old.version, command.version)
                            state(old.phase == TrainingPhase.CLOSED, "Возобновить можно завершённую тренировку")
                            sqlUpdate(c, "UPDATE trainings SET status='REVIEW' WHERE group_id=? AND id=?", a.groupId, trainingId)
                        }
                        is SettlementCommand.CancelTraining -> {
                            stale(old.version, command.version)
                            state(old.phase != TrainingPhase.CANCELLED, "Тренировка уже отменена")
                            entries = reverseTraining(c, a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE trainings SET status='CANCELLED',applied_version=0 WHERE group_id=? AND id=?", a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE training_players SET applied_playing=0 WHERE group_id=? AND training_id=?", a.groupId, trainingId)
                        }
                        else -> error("Unhandled training command")
                    }
                    sqlUpdate(c, "UPDATE trainings SET version=? WHERE group_id=? AND id=?", version, a.groupId, trainingId)
                }
                after = json.encodeToString(training(c, a.groupId, trainingId))
            }
        }
        // Validate the whole batch before writing any ledger rows. The transaction also covers the edited records.
        applyEntries(database.balances(c, a.groupId).mapKeys { ParticipantId(it.key.toString()) }, entries)
        sqlUpdate(c, """INSERT INTO actions(group_id,request_id,actor_id,kind,training_id,transfer_id,payload_json,before_json,after_json,result_version,occurred_at,needs_delivery)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", a.groupId, requestId, a.userId, command.javaClass.simpleName, trainingId, transferId, payload, before, after, version, clock.instant().toString(), trainingId != null)
        val actionId = sqlQuery(c, "SELECT last_insert_rowid()") { it.getLong(1) }.single()
        entries.forEachIndexed { index, entry ->
            sqlUpdate(c, "INSERT INTO balance_entries(action_id,group_id,entry_index,user_id,amount) VALUES(?,?,?,?,?)", actionId, a.groupId, index, entry.participant.value.toLong(), entry.amount)
        }
        ActionReceipt(actionId, version)
    }

    private fun changeAttendance(row: Attendance, command: SettlementCommand.ChangeAttendance): Attendance = when (command.change) {
        AttendanceChange.JOIN -> row.copy(playing = true)
        AttendanceChange.LEAVE -> row.copy(playing = false, guestMinutes = 0)
        AttendanceChange.MARK_PAID -> if (row.paid == 0L) row.copy(paid = 300) else row
        AttendanceChange.ADJUST_PAID -> {
            require(command.value == 50L || command.value == -50L)
            row.copy(paid = Math.addExact(row.paid, command.value).coerceAtLeast(0))
        }
        AttendanceChange.SET_PAID -> { require(command.value >= 0); row.copy(paid = command.value) }
        AttendanceChange.ADJUST_MINUTES -> {
            require(command.value == 30L || command.value == -30L)
            state(row.playing, "Сначала присоединись к тренировке")
            row.copy(minutes = Math.addExact(row.minutes, command.value).coerceAtLeast(30))
        }
        AttendanceChange.SET_MINUTES -> {
            require(command.value > 0 && command.value % 30 == 0L)
            state(row.playing, "Сначала добавь игрока в тренировку")
            row.copy(minutes = command.value)
        }
        AttendanceChange.GUEST -> {
            state(row.playing, "Гость добавляется к играющему участнику")
            require(command.value in 0L..1L)
            row.copy(guestMinutes = if (command.value == 0L) 0 else row.minutes)
        }
        AttendanceChange.SET_GUEST_MINUTES -> {
            state(row.playing, "Гость добавляется к играющему участнику")
            require(command.value >= 0 && command.value % 30 == 0L)
            row.copy(guestMinutes = command.value)
        }
    }

    private fun transferParty(c: Connection, a: Access, parties: Set<Long>, onBehalfOf: Long?, absent: AbsentAccount?): Long {
        if (onBehalfOf == null) { allowed(a.userId in parties, "Записать или уточнить перевод может одна из его сторон"); return a.userId }
        requireAdmin(c, a)
        allowed(onBehalfOf in parties && absent == AbsentAccount(a.groupId, onBehalfOf), "Представлять можно только участника, чей выход из этой группы проверен через Telegram")
        return onBehalfOf
    }
    private fun transferEntries(t: MoneyTransfer) = listOf(BalanceEntry(ParticipantId(t.from.toString()), t.amount), BalanceEntry(ParticipantId(t.to.toString()), -t.amount))
    private fun reverseTraining(c: Connection, group: Long, id: String) = database.trainingEffect(c, group, id).map { BalanceEntry(ParticipantId(it.key.toString()), -it.value) }
    private fun validateDetails(title: String, date: String, time: String) {
        require(title == title.trim() && title.length in 1..100) { "Название: от 1 до 100 символов" }
        LocalDate.parse(date); LocalTime.parse(time)
    }
    private fun editable(t: TrainingRecord) = state(t.phase in setOf(TrainingPhase.OPEN, TrainingPhase.REVIEW), "Для правки завершённой тренировки сначала нажми «Возобновить»")

    fun training(a: Access, id: String): TrainingRecord = database.read { known(it, a.groupId, a.userId); training(it, a.groupId, id) }
    internal fun training(c: Connection, group: Long, id: String): TrainingRecord {
        val players = sqlQuery(c, "SELECT * FROM training_players WHERE group_id=? AND training_id=? ORDER BY ordinal", group, id) {
            Attendance(it.getLong("user_id"), it.getBoolean("playing"), it.getLong("minutes"), it.getLong("guest_minutes"), it.getLong("paid"), it.getInt("ordinal"), it.getBoolean("applied_playing"))
        }
        return sqlQuery(c, "SELECT * FROM trainings WHERE group_id=? AND id=?", group, id) {
            TrainingRecord(group, id, it.getString("title"), it.getString("played_on"), it.getString("starts_at"), TrainingPhase.valueOf(it.getString("status")), it.getLong("version"), it.getLong("applied_version"), it.getLong("created_by"), players)
        }.singleOrNull() ?: invalid("Тренировка не найдена в этой группе")
    }
    fun trainings(a: Access, page: Int = 0, mine: Boolean = false, unfinished: Boolean = false): Page<TrainingRecord> = database.read { c ->
        known(c, a.groupId, a.userId)
        val condition = "FROM trainings t WHERE t.group_id=?" + (if (mine) " AND EXISTS (SELECT 1 FROM training_players p WHERE p.group_id=t.group_id AND p.training_id=t.id AND p.user_id=? AND (p.playing=1 OR p.applied_playing=1 OR p.paid>0))" else "") + (if (unfinished) " AND t.status IN ('OPEN','REVIEW')" else "")
        val args = if (mine) arrayOf<Any>(a.groupId, a.userId) else arrayOf<Any>(a.groupId)
        val total = count(c, "SELECT COUNT(*) $condition", *args)
        val index = pageIndex(page, total)
        val ids = sqlQuery(c, "SELECT t.id $condition ORDER BY t.played_on DESC,t.starts_at DESC,t.id LIMIT 8 OFFSET ?", *args, index * 8) { it.getString(1) }
        Page(ids.map { training(c, a.groupId, it) }, total, index)
    }
    fun transfer(a: Access, id: String): MoneyTransfer = database.read { known(it, a.groupId, a.userId); transfer(it, a.groupId, id) }
    private fun transfer(c: Connection, group: Long, id: String): MoneyTransfer = sqlQuery(c, "SELECT * FROM transfers WHERE group_id=? AND id=?", group, id) {
        MoneyTransfer(group, id, it.getLong("from_user"), it.getLong("to_user"), it.getLong("amount"), it.getString("occurred_on"), it.getString("note"), PaymentStatus.valueOf(it.getString("status")), it.getLong("version"), it.getString("reviewer")?.toLong(), it.getString("review_party")?.toLong(), it.getLong("created_by"))
    }.singleOrNull() ?: invalid("Перевод не найден в этой группе")
    fun transfers(a: Access, page: Int = 0): Page<MoneyTransfer> = database.read { c ->
        known(c, a.groupId, a.userId)
        val condition = "FROM transfers WHERE group_id=? AND (from_user=? OR to_user=?)"
        val args = arrayOf<Any>(a.groupId, a.userId, a.userId)
        val total = count(c, "SELECT COUNT(*) $condition", *args)
        val index = pageIndex(page, total)
        val ids = sqlQuery(c, "SELECT id $condition ORDER BY occurred_on DESC,id LIMIT 8 OFFSET ?", *args, index * 8) { it.getString(1) }
        Page(ids.map { transfer(c, a.groupId, it) }, total, index)
    }
    fun balances(a: Access): Map<Long, Long> = database.read { known(it, a.groupId, a.userId); database.balances(it, a.groupId) }
    fun roster(a: Access, page: Int = 0, playedBefore: Boolean = false, balanceOnly: Boolean = false, settled: Boolean = false): Page<AccountBalance> = database.read { c ->
        known(c, a.groupId, a.userId)
        val balances = database.balances(c, a.groupId)
        val rows = sqlQuery(c, """SELECT u.*,gu.present,(SELECT COUNT(*) FROM training_players p
            JOIN trainings t ON t.group_id=p.group_id AND t.id=p.training_id WHERE p.group_id=gu.group_id AND p.user_id=gu.user_id
            AND p.applied_playing=1 AND t.status IN ('CLOSED','REVIEW')) AS attendance
            FROM group_users gu JOIN users u ON u.id=gu.user_id WHERE gu.group_id=? AND u.is_bot=0
            ORDER BY attendance DESC,u.first_name COLLATE NOCASE,u.id""", a.groupId) {
            AccountBalance(readAccount(it), balances[it.getLong("id")] ?: 0, it.getInt("attendance"), it.getBoolean("present"))
        }.filter { (!playedBefore || it.attendance > 0) && (!balanceOnly || if (settled) it.balance == 0L && it.attendance > 0 else it.balance != 0L) }
        val index = pageIndex(page, rows.size)
        Page(rows.drop(index * 8).take(8), rows.size, index)
    }
    fun history(a: Access, trainingId: String? = null, page: Int = 0): Page<AuditAction> = database.read { c ->
        known(c, a.groupId, a.userId)
        val condition = "FROM actions WHERE group_id=?" + if (trainingId != null) " AND training_id=?" else ""
        val args = if (trainingId != null) arrayOf<Any>(a.groupId, trainingId) else arrayOf<Any>(a.groupId)
        val total = count(c, "SELECT COUNT(*) $condition", *args)
        val index = pageIndex(page, total)
        Page(sqlQuery(c, "SELECT * $condition ORDER BY id DESC LIMIT 8 OFFSET ?", *args, index * 8) {
            AuditAction(it.getLong("id"), a.groupId, it.getLong("actor_id"), it.getString("kind"), it.getString("training_id"), it.getString("transfer_id"), it.getString("before_json"), it.getString("after_json"), it.getString("occurred_at"))
        }, total, index)
    }
    private fun count(c: Connection, sql: String, vararg args: Any?) = sqlQuery(c, sql, *args) { it.getInt(1) }.single()
    private fun pageIndex(page: Int, total: Int) = page.coerceIn(0, maxOf(0, (total - 1) / 8))
    private fun stale(actual: Long, expected: Long) = checkAccounting(actual == expected, ErrorCode.STALE_VERSION, "Данные изменились. Открой тренировку или перевод заново")
    private fun allowed(condition: Boolean, message: String) = checkAccounting(condition, ErrorCode.FORBIDDEN, message)
    private fun state(condition: Boolean, message: String) = checkAccounting(condition, ErrorCode.INVALID_STATE, message)
    private fun invalid(message: String): Nothing = throw AccountingException(ErrorCode.INVALID_INPUT, message)
}
