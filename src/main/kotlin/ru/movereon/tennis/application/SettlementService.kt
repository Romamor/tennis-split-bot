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
    fun groupAccount(a:Access,user:Long):Account = database.read { c ->
        known(c,a.groupId,a.userId); known(c,a.groupId,user); account(c,user)
    }
    private fun account(c: Connection, id: Long): Account = sqlQuery(c, "SELECT * FROM users WHERE id=?", id, map = ::readAccount)
        .singleOrNull() ?: invalid("Аккаунт ещё не известен боту")
    private fun readAccount(r: ResultSet) = Account(r.getLong("id"), r.getString("first_name"), r.getString("last_name"), r.getString("username"), r.getBoolean("is_bot"))
    fun group(id: Long): SettlementGroup = database.read { c ->
        sqlQuery(c, "SELECT * FROM groups WHERE id=?", id) { SettlementGroup(it.getLong("id"), it.getString("title"), it.getString("time_zone")) }
            .singleOrNull() ?: invalid("Группа не найдена")
    }
    fun groups(userId: Long, page: Int = 0): Page<SettlementGroup> = database.read { c ->
        val condition = "FROM groups g JOIN group_users u ON u.group_id=g.id WHERE u.user_id=? AND u.present=1"
        val total = count(c, "SELECT COUNT(*) $condition", userId)
        val index = pageIndex(page, total)
        Page(sqlQuery(c, "SELECT g.* $condition ORDER BY g.title,g.id LIMIT 8 OFFSET ?", userId, index * 8) {
            SettlementGroup(it.getLong("id"), it.getString("title"), it.getString("time_zone"))
        }, total, index)
    }
    fun isAdmin(access: Access): Boolean = database.read { admin(it, access) }
    fun canFinish(a:Access,t:TrainingRecord):Boolean = database.read { canFinish(it,a,t) }
    private fun present(c:Connection,a:Access)=count(c,"SELECT COUNT(*) FROM group_users WHERE group_id=? AND user_id=? AND present=1",a.groupId,a.userId)==1
    private fun canFinish(c:Connection,a:Access,t:TrainingRecord)=t.groupId==a.groupId && present(c,a) && (t.createdBy==a.userId || admin(c,a))
    fun administrators(a:Access,telegramAdmins:Set<Long>,candidates:Boolean=false,page:Int=0):Page<GroupRoleEntry> = database.read { c ->
        known(c,a.groupId,a.userId)
        allowed(a.telegramAdmin,"Управлять назначениями могут администраторы Telegram-группы")
        val rows=sqlQuery(c,"""SELECT u.*,ga.user_id AS appointed FROM group_users gu JOIN users u ON u.id=gu.user_id
            LEFT JOIN group_admins ga ON ga.group_id=gu.group_id AND ga.user_id=gu.user_id
            WHERE gu.group_id=? AND u.is_bot=0""",a.groupId) {
            val account=readAccount(it)
            GroupRoleEntry(account,when { account.id in telegramAdmins -> GroupRole.SUPERADMIN;it.getString("appointed")!=null -> GroupRole.ADMIN;else -> GroupRole.MEMBER })
        }.filter { if (candidates) it.role==GroupRole.MEMBER else it.role!=GroupRole.MEMBER }
            .sortedWith(compareBy<GroupRoleEntry> { it.role.ordinal }.thenBy { it.account.name.lowercase() }.thenBy { it.account.id })
        val index=pageIndex(page,rows.size)
        Page(rows.drop(index*8).take(8),rows.size,index)
    }
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
                val similar = recentSimilar(c,a.groupId,command.from,command.to,command.amount)
                if (similar.isNotEmpty() && !command.allowSimilar) throw DuplicateTransfer(similar)
                require(count(c, "SELECT COUNT(*) FROM transfers WHERE group_id=? AND id=?", a.groupId, transferId) == 0) { "Перевод уже записан" }
                sqlUpdate(c, """INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,note,status,version,created_by,created_at)
                    VALUES(?,?,?,?,?,?,?,'ACTIVE',1,?,?)""", a.groupId, transferId, command.from, command.to, command.amount, command.date, command.note, a.userId, clock.instant().toString())
                entries = transferEntries(transfer(c, a.groupId, transferId))
                after = json.encodeToString(transfer(c, a.groupId, transferId))
            }
            is SettlementCommand.EditTransferAmount -> {
                transferId=command.id
                val old=transfer(c,a.groupId,transferId)
                transferParty(c,a,setOf(old.from,old.to),null,null)
                stale(old.version,command.version)
                state(old.status!=PaymentStatus.CANCELLED,"Отменённый перевод нельзя исправить")
                require(command.amount>0) { "Укажи положительную сумму" }
                if(command.amount==old.amount) return@write ActionReceipt(0,old.version)
                val similar=recentSimilar(c,a.groupId,old.from,old.to,command.amount,old.id)
                if(similar.isNotEmpty() && !command.allowSimilar) throw DuplicateTransfer(similar)
                before=json.encodeToString(old)
                version=Math.addExact(old.version,1)
                sqlUpdate(c,"UPDATE transfers SET amount=?,version=? WHERE group_id=? AND id=?",command.amount,version,a.groupId,old.id)
                val changed=transfer(c,a.groupId,old.id)
                if(old.status==PaymentStatus.ACTIVE)
                    entries=transferEntries(old).map { it.copy(amount=-it.amount) }+transferEntries(changed)
                after=json.encodeToString(changed)
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
                    allowed(present(c,a), "Создавать тренировку может участник этой группы")
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
                        is SettlementCommand.SaveAttendance -> command.id
                        is SettlementCommand.AddPlayers -> command.id
                        is SettlementCommand.FinishTraining -> command.id
                        is SettlementCommand.ReopenTraining -> command.id
                        is SettlementCommand.CancelTraining -> command.id
                        is SettlementCommand.RestoreTraining -> command.id
                        else -> error("Unhandled training command")
                    }
                    val old = training(c, a.groupId, trainingId)
                    before = json.encodeToString(old)
                    version = Math.addExact(old.version, 1)
                    if(command is SettlementCommand.FinishTraining)
                        allowed(canFinish(c,a,old), "Учесть тренировку может её создатель или администратор этой группы")
                    else if (command !is SettlementCommand.ChangeAttendance && command !is SettlementCommand.SaveAttendance) requireAdmin(c, a)
                    when (command) {
                        is SettlementCommand.AddPlayers -> {
                            stale(old.version,command.version); editable(old)
                            require(command.users.isNotEmpty() && command.users.distinct().size==command.users.size)
                            command.users.forEach { known(c,a.groupId,it) }
                            val additions=command.users.filter { id -> old.players.none { it.userId==id && it.playing } }
                            if (additions.isEmpty()) return@write ActionReceipt(0,old.version)
                            var ordinal=(old.players.maxOfOrNull { it.ordinal } ?: -1)+1
                            additions.forEach { id ->
                                sqlUpdate(c,"""INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal)
                                    VALUES(?,?,?,1,0,0,0,0,?) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET
                                    playing=1,minutes=0,guest_minutes=0,guest_count=0""",a.groupId,trainingId,id,ordinal++)
                            }
                            validateDraftTraining(training(c,a.groupId,trainingId).calculation())
                        }
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
                            if (changed==row) return@write ActionReceipt(0,old.version)
                            sqlUpdate(c, """INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal)
                                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET
                                playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,guest_count=excluded.guest_count,paid=excluded.paid""",
                                a.groupId, trainingId, changed.userId, changed.playing, changed.minutes, changed.guestMinutes, changed.guestCount, changed.paid, changed.ordinal)
                            validateDraftTraining(training(c, a.groupId, trainingId).calculation())
                        }
                        is SettlementCommand.SaveAttendance -> {
                            editable(old)
                            allowed(command.userId == a.userId || admin(c,a), "Можно менять только свои время и оплату")
                            known(c,a.groupId,command.userId)
                            val current=old.players.firstOrNull { it.userId==command.userId }
                            checkAccounting(current==command.expected,ErrorCode.STALE_VERSION,"Эти данные уже изменили. Обнови форму перед сохранением")
                            val input=command.attendance
                            require(input.userId==command.userId && input.minutes>=0 && input.minutes%30==0L && input.guestMinutes>=0 && input.guestMinutes%30==0L && input.paid>=0)
                            require(input.guestCount in 0..99 && (input.playing || input.guestMinutes==0L && input.guestCount==0))
                            if(input==current) return@write ActionReceipt(0,old.version)
                            val row=(current ?: Attendance(command.userId,false,ordinal=(old.players.maxOfOrNull { it.ordinal } ?: -1)+1))
                                .copy(playing=input.playing,minutes=input.minutes,guestMinutes=if(input.guestCount>0) input.minutes else 0,guestCount=input.guestCount,paid=input.paid)
                            if (row==current || current==null && !row.playing && row.paid==0L) return@write ActionReceipt(0,old.version)
                            sqlUpdate(c,"""INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal)
                                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET
                                playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,guest_count=excluded.guest_count,paid=excluded.paid""",
                                a.groupId,trainingId,row.userId,row.playing,row.minutes,row.guestMinutes,row.guestCount,row.paid,row.ordinal)
                            validateDraftTraining(training(c,a.groupId,trainingId).calculation())
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
                            state(old.phase == TrainingPhase.CLOSED, "Открыть исправление можно для учтённой тренировки")
                            sqlUpdate(c, "UPDATE trainings SET status='REVIEW' WHERE group_id=? AND id=?", a.groupId, trainingId)
                        }
                        is SettlementCommand.CancelTraining -> {
                            stale(old.version, command.version)
                            state(old.phase != TrainingPhase.CANCELLED, "Тренировка уже отменена")
                            entries = reverseTraining(c, a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE trainings SET status='CANCELLED',applied_version=0 WHERE group_id=? AND id=?", a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE training_players SET applied_playing=0 WHERE group_id=? AND training_id=?", a.groupId, trainingId)
                        }
                        is SettlementCommand.RestoreTraining -> {
                            stale(old.version, command.version)
                            state(old.phase == TrainingPhase.CANCELLED, "Восстановить можно только отменённую тренировку")
                            // Cancellation already reversed the ledger. Keep the saved input for explicit accounting.
                            sqlUpdate(c, "UPDATE trainings SET status='OPEN' WHERE group_id=? AND id=?", a.groupId, trainingId)
                        }
                        else -> error("Unhandled training command")
                    }
                    sqlUpdate(c, "UPDATE trainings SET version=? WHERE group_id=? AND id=?", version, a.groupId, trainingId)
                }
                refreshAttendance(c,a.groupId)
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

    fun previewAttendance(row: Attendance, change: AttendanceChange, value: Long=0): Attendance =
        changeAttendance(row,SettlementCommand.ChangeAttendance("input",row.userId,change,value))
    private fun changeAttendance(row: Attendance, command: SettlementCommand.ChangeAttendance): Attendance {
        fun participating() = state(row.playing, "Сначала присоединись к тренировке")
        fun adjusted(value:Long,delta:Long):Long = try { Math.addExact(value,delta).coerceAtLeast(0) }
            catch(_:ArithmeticException) { throw AccountingException(ErrorCode.OUT_OF_RANGE,"Слишком большое значение") }
        val changed=when (command.change) {
            AttendanceChange.JOIN -> if(row.playing) row else row.copy(playing=true,minutes=0)
            AttendanceChange.LEAVE -> row.copy(playing=false,minutes=0,guestMinutes=0,guestCount=0)
            AttendanceChange.LEAVE_AND_CLEAR_PAYMENT -> {
                checkAccounting(row.paid==command.value,ErrorCode.STALE_VERSION,"Оплата изменилась. Открой форму заново")
                row.copy(playing=false,minutes=0,guestMinutes=0,guestCount=0,paid=0)
            }
            AttendanceChange.MARK_PAID -> {
                participating()
                if(row.paid==0L) row.copy(paid=300) else row
            }
            AttendanceChange.ADJUST_PAID -> {
                require(command.value in listOf(-1000L,-100L,-50L,-10L,-5L,-1L,1L,5L,10L,50L,100L,1000L))
                state(row.playing || row.paid>0,"Сначала присоединись к тренировке")
                row.copy(paid=adjusted(row.paid,command.value))
            }
            AttendanceChange.SET_PAID -> {
                require(command.value>=0)
                row.copy(paid=command.value)
            }
            AttendanceChange.ADJUST_MINUTES -> {
                require(command.value in listOf(-60L,-30L,30L,60L));participating()
                row.copy(minutes=adjusted(row.minutes,command.value))
            }
            AttendanceChange.SET_MINUTES -> {
                require(command.value>=0 && command.value%30==0L);participating()
                row.copy(minutes=command.value)
            }
            AttendanceChange.GUEST, AttendanceChange.ADJUST_GUESTS -> {
                participating()
                val count=if(command.change==AttendanceChange.GUEST) command.value else {
                    require(command.value in listOf(-1L,1L));row.guestCount.toLong()+command.value
                }
                require(count in 0L..99L) { "Можно добавить от 0 до 99 гостей" }
                row.copy(guestCount=count.toInt())
            }
            AttendanceChange.SET_GUEST_MINUTES -> {
                // An old button cannot give a guest a different duration under the new rules.
                require(command.value==row.minutes || command.value==0L) { "Гости играют столько же, сколько пригласивший" }
                participating();row.copy(guestCount=if(command.value==0L) 0 else maxOf(1,row.guestCount))
            }
        }
        return if(changed==row) row else changed.copy(guestMinutes=if(changed.guestCount>0) changed.minutes else 0)
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
    private fun editable(t: TrainingRecord) = state(t.phase in setOf(TrainingPhase.OPEN, TrainingPhase.REVIEW), "Для правки учтённой тренировки сначала нажми «Исправить тренировку»")

    fun training(a: Access, id: String): TrainingRecord = database.read { known(it, a.groupId, a.userId); training(it, a.groupId, id) }
    internal fun training(c: Connection, group: Long, id: String): TrainingRecord {
        val players = sqlQuery(c, "SELECT * FROM training_players WHERE group_id=? AND training_id=? ORDER BY ordinal", group, id) {
            Attendance(it.getLong("user_id"), it.getBoolean("playing"), it.getLong("minutes"), it.getLong("guest_minutes"), it.getLong("paid"), it.getInt("ordinal"), it.getBoolean("applied_playing"),it.getInt("guest_count"))
        }
        return sqlQuery(c, "SELECT * FROM trainings WHERE group_id=? AND id=?", group, id) {
            TrainingRecord(group, id, it.getString("title"), it.getString("played_on"), it.getString("starts_at"), TrainingPhase.valueOf(it.getString("status")), it.getLong("version"), it.getLong("applied_version"), it.getLong("created_by"), players)
        }.singleOrNull() ?: invalid("Тренировка не найдена в этой группе")
    }
    fun trainings(a: Access, page: Int = 0, mine: Boolean = false, unfinished: Boolean = false): Page<TrainingRecord> = database.read { c ->
        known(c, a.groupId, a.userId)
        val condition = "FROM trainings t WHERE t.group_id=?" + (if (mine) " AND ((t.created_by=? AND t.status IN ('OPEN','REVIEW')) OR EXISTS (SELECT 1 FROM training_players p WHERE p.group_id=t.group_id AND p.training_id=t.id AND p.user_id=? AND (p.playing=1 OR p.applied_playing=1 OR p.paid>0)))" else "") + (if (unfinished) " AND t.status IN ('OPEN','REVIEW')" else "")
        val args = if (mine) arrayOf<Any>(a.groupId, a.userId, a.userId) else arrayOf<Any>(a.groupId)
        val total = count(c, "SELECT COUNT(*) $condition", *args)
        val index = pageIndex(page, total)
        val ids = sqlQuery(c, "SELECT t.id $condition ORDER BY t.played_on DESC,t.starts_at DESC,t.id LIMIT 8 OFFSET ?", *args, index * 8) { it.getString(1) }
        Page(ids.map { training(c, a.groupId, it) }, total, index)
    }
    private fun recentSimilar(c:Connection,group:Long,from:Long,to:Long,amount:Long,exclude:String=""):List<String> =
        sqlQuery(c,"""SELECT id FROM transfers WHERE group_id=? AND from_user=? AND to_user=? AND amount=?
            AND id<>? AND status<>'CANCELLED' AND julianday(created_at)>=julianday(?)
            ORDER BY created_at DESC,id LIMIT 8""",group,from,to,amount,exclude,clock.instant().minusSeconds(86400).toString()) { it.getString(1) }
    fun rosterIds(a:Access):List<Long> = database.read { c ->
        known(c,a.groupId,a.userId)
        sqlQuery(c,"""SELECT u.id FROM group_users gu JOIN users u ON u.id=gu.user_id WHERE gu.group_id=? AND u.is_bot=0
            ORDER BY gu.attendance_count DESC,u.first_name COLLATE NOCASE,u.id""",a.groupId) { it.getLong(1) }
    }
    fun groupAccounts(a:Access):List<Account> = database.read { c ->
        known(c,a.groupId,a.userId)
        sqlQuery(c,"SELECT u.* FROM users u JOIN group_users gu ON gu.user_id=u.id WHERE gu.group_id=? AND u.is_bot=0",a.groupId,map=::readAccount)
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
    fun roster(a: Access, page: Int = 0, playedBefore: Boolean = false, balanceOnly: Boolean = false, settled: Boolean = false, exclude: Set<Long> = emptySet()): Page<AccountBalance> = database.read { c ->
        known(c, a.groupId, a.userId)
        val balances = database.balances(c, a.groupId)
        val rows = sqlQuery(c, """SELECT u.*,gu.present,gu.attendance_count AS attendance,gu.has_played
            FROM group_users gu JOIN users u ON u.id=gu.user_id WHERE gu.group_id=? AND u.is_bot=0
            ORDER BY attendance DESC,u.first_name COLLATE NOCASE,u.id""", a.groupId) {
            AccountBalance(readAccount(it), balances[it.getLong("id")] ?: 0, it.getInt("attendance"), it.getBoolean("present"),it.getBoolean("has_played"))
        }.filter { it.account.id !in exclude && (!playedBefore || it.attendance > 0) && (!balanceOnly || if (settled) it.balance == 0L && it.hasPlayed else it.balance != 0L) }
        val index = pageIndex(page, rows.size)
        Page(rows.drop(index * 8).take(8), rows.size, index)
    }
    fun history(a: Access, trainingId: String? = null, page: Int = 0, transferId:String?=null): Page<AuditAction> = database.read { c ->
        known(c, a.groupId, a.userId)
        require(trainingId==null || transferId==null)
        if(transferId!=null) {
            val t=transfer(c,a.groupId,transferId)
            allowed(a.userId in setOf(t.from,t.to) || admin(c,a),"История перевода доступна его сторонам")
        }
        val condition = "FROM actions WHERE group_id=?" + if(trainingId!=null) " AND training_id=?" else if(transferId!=null) " AND transfer_id=?" else ""
        val id=trainingId ?: transferId
        val args=if(id!=null) arrayOf<Any>(a.groupId,id) else arrayOf<Any>(a.groupId)
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
