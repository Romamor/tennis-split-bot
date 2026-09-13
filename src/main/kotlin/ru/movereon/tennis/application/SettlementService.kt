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
    fun knownGroups():List<SettlementGroup> = database.read { c ->
        sqlQuery(c,"SELECT id,title,time_zone FROM groups ORDER BY title,id") { SettlementGroup(it.getLong(1),it.getString(2),it.getString(3)) }
    }
    fun trainingDefaults(user:Long):TrainingDefaults = database.read { c ->
        account(c,user)
        sqlQuery(c,"SELECT training_title,training_time FROM users WHERE id=?",user) { TrainingDefaults(it.getString(1),it.getString(2)) }.single()
    }
    fun updateTrainingDefaults(user:Long,update:DefaultTrainingUpdate) = database.write { c ->
        account(c,user)
        val column=when(update.field) {
            "title" -> { require(update.value==update.value.trim() && update.value.length in 1..100) { "Название: от 1 до 100 символов" };"training_title" }
            "time" -> { require(update.value.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "Время: ЧЧ:ММ" };LocalTime.parse(update.value);"training_time" }
            else -> error("Unknown default field")
        }
        val current=sqlQuery(c,"SELECT $column FROM users WHERE id=?",user) { it.getString(1) }.single()
        if(current!=update.value) {
            checkAccounting(current==update.expected,ErrorCode.STALE_VERSION,"Настройка уже изменилась. Открой её заново.")
            sqlUpdate(c,"UPDATE users SET $column=? WHERE id=?",update.value,user)
        }
    }
    fun myTrainings(user:Long,page:Int=0):MyTrainingPage = database.read { c ->
        account(c,user)
        val condition="""FROM trainings t LEFT JOIN training_players p ON p.group_id=t.group_id AND p.training_id=t.id AND p.user_id=?
            WHERE (t.created_by=? AND t.status='OPEN') OR p.playing=1 OR p.applied_playing=1 OR p.paid>0"""
        var total=0
        var minutes=java.math.BigInteger.ZERO
        var paid=java.math.BigInteger.ZERO
        sqlEach(c,"SELECT CASE WHEN p.playing=1 THEN p.minutes ELSE 0 END,COALESCE(p.paid,0) $condition",user,user) {
            total++;minutes+=it.getLong(1).toBigInteger();paid+=it.getLong(2).toBigInteger()
        }
        val index=page.coerceIn(0,maxOf(0,(total-1)/3))
        val ids=sqlQuery(c,"SELECT t.group_id,t.id $condition ORDER BY t.created_at DESC,t.id DESC,t.group_id LIMIT 3 OFFSET ?",user,user,index*3) { it.getLong(1) to it.getString(2) }
        MyTrainingPage(Page(ids.map { readTraining(c,it.first,it.second) },total,index,3),minutes.toAmount(),paid.toAmount())
    }
    fun isAdmin(access: Access): Boolean = database.read { admin(it, access) }
    fun canFinish(a:Access,t:TrainingRecord):Boolean = canEdit(a,t)
    fun canEdit(a:Access,t:TrainingRecord):Boolean = database.read { canManageTraining(it,a,t) }
    fun requireOpen(t:TrainingRecord) = editable(t)
    private fun present(c:Connection,a:Access)=count(c,"SELECT COUNT(*) FROM group_users WHERE group_id=? AND user_id=? AND present=1",a.groupId,a.userId)==1
    private fun canManageTraining(c:Connection,a:Access,t:TrainingRecord)=t.groupId==a.groupId && present(c,a) && (t.createdBy==a.userId || admin(c,a))
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
            is SettlementCommand.SetDefaultStartTime -> invalid("Настройки тренировки теперь личные. Открой «Настройки».")
            is SettlementCommand.SetAdministrator -> {
                allowed(a.telegramAdmin, "Назначать администраторов могут только администраторы этой группы Telegram")
                known(c, a.groupId, command.userId)
                before = (count(c, "SELECT COUNT(*) FROM group_admins WHERE group_id=? AND user_id=?", a.groupId, command.userId) > 0).toString()
                if (command.enabled) sqlUpdate(c, """INSERT INTO group_admins(group_id,user_id,granted_by,granted_at) VALUES(?,?,?,?)
                    ON CONFLICT(group_id,user_id) DO NOTHING""", a.groupId, command.userId, a.userId, clock.instant().toString())
                else sqlUpdate(c, "DELETE FROM group_admins WHERE group_id=? AND user_id=?", a.groupId, command.userId)
                after = command.enabled.toString()
            }
            is SettlementCommand.SendPayment -> {
                allowed(present(c,a),"Отправлять платёж может текущий участник группы")
                known(c,a.groupId,command.to);checkId(command.id)
                val available=availablePayments(c,database,a.groupId).firstOrNull { it.from.value==a.userId.toString() && it.to.value==command.to.toString() }
                state(available!=null && available.amount==command.amount,"Платёж больше недоступен или сумма изменилась. Обнови список платежей")
                transferId=command.id
                sqlUpdate(c,"""INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,note,status,version,reviewer,review_party,created_by,created_at)
                    VALUES(?,?,?,?,?,?,'','REVIEW',1,?,?,?,?)""",a.groupId,transferId,a.userId,command.to,command.amount,
                    LocalDate.now(clock.withZone(ZoneId.of(sqlQuery(c,"SELECT time_zone FROM groups WHERE id=?",a.groupId) { it.getString(1) }.single()))).toString(),a.userId,a.userId,a.userId,clock.instant().toString())
                after=json.encodeToString(transfer(c,a.groupId,transferId))
            }
            is SettlementCommand.SendOtherPayment -> {
                allowed(present(c,a),"Отправлять платёж может текущий участник группы")
                known(c,a.groupId,command.to);checkId(command.id)
                require(command.amount>0 && command.to!=a.userId) { "Укажи положительную сумму и разных участников" }
                val similar=recentSimilar(c,a.groupId,a.userId,command.to,command.amount)
                if(similar.isNotEmpty() && !command.allowSimilar) throw DuplicateTransfer(similar)
                transferId=command.id
                sqlUpdate(c,"""INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,note,status,version,reviewer,review_party,created_by,created_at)
                    VALUES(?,?,?,?,?,?,'','REVIEW',1,?,?,?,?)""",a.groupId,transferId,a.userId,command.to,command.amount,
                    paymentDate(c,a.groupId),a.userId,a.userId,a.userId,clock.instant().toString())
                after=json.encodeToString(transfer(c,a.groupId,transferId))
            }
            is SettlementCommand.RecordAdminPayment -> {
                allowed(present(c,a),"Записывать платёж может текущий администратор группы");requireAdmin(c,a)
                known(c,a.groupId,command.from);known(c,a.groupId,command.to);checkId(command.id)
                require(command.amount>0 && command.from!=command.to) { "Укажи положительную сумму и разных участников" }
                transferId=command.id
                sqlUpdate(c,"""INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,note,status,version,created_by,created_at)
                    VALUES(?,?,?,?,?,?,'','ACTIVE',1,?,?)""",a.groupId,transferId,command.from,command.to,command.amount,
                    paymentDate(c,a.groupId),a.userId,clock.instant().toString())
                entries=transferEntries(transfer(c,a.groupId,transferId))
                after=json.encodeToString(transfer(c,a.groupId,transferId))
            }
            is SettlementCommand.ReceivePayment -> {
                transferId=command.id
                val old=transfer(c,a.groupId,transferId)
                allowed(present(c,a) && a.userId==old.to,"Принять платёж может только его получатель")
                if(old.status==PaymentStatus.ACTIVE) return@write ActionReceipt(0,old.version)
                state(old.status==PaymentStatus.REVIEW,"Этот платёж недоступен для принятия")
                before=json.encodeToString(old);version=Math.addExact(old.version,1)
                sqlUpdate(c,"UPDATE transfers SET status='ACTIVE',version=?,reviewer=NULL,review_party=NULL WHERE group_id=? AND id=?",version,a.groupId,transferId)
                entries=transferEntries(old)
                after=json.encodeToString(transfer(c,a.groupId,transferId))
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
                state(count(c,"SELECT COUNT(*) FROM actions WHERE group_id=? AND transfer_id=? AND kind IN ('SendPayment','SendOtherPayment','RecordAdminPayment')",a.groupId,transferId)==0,"Используй новое меню «Мои финансы»")
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
                state(count(c,"SELECT COUNT(*) FROM actions WHERE group_id=? AND transfer_id=? AND kind IN ('SendPayment','SendOtherPayment','RecordAdminPayment')",a.groupId,transferId)==0,"Используй новое меню «Мои финансы»")
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
                        is SettlementCommand.RemovePlayer -> command.id
                        is SettlementCommand.FinishTraining -> command.id
                        is SettlementCommand.ReopenTraining -> command.id
                        is SettlementCommand.CancelTraining -> command.id
                        is SettlementCommand.RestoreTraining -> command.id
                        else -> error("Unhandled training command")
                    }
                    val old = readTraining(c, a.groupId, trainingId)
                    before = json.encodeToString(old)
                    version = Math.addExact(old.version, 1)
                    if(command is SettlementCommand.FinishTraining)
                        allowed(canManageTraining(c,a,old), "Учесть тренировку может её создатель или администратор этой группы")
                    else if (command is SettlementCommand.EditTraining || command is SettlementCommand.AddPlayers || command is SettlementCommand.RemovePlayer || command is SettlementCommand.ReopenTraining || command is SettlementCommand.CancelTraining || command is SettlementCommand.RestoreTraining)
                        allowed(canManageTraining(c,a,old), "Редактировать тренировку может её создатель или администратор этой группы")
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
                            validateDraftTraining(readTraining(c,a.groupId,trainingId).calculation())
                        }
                        is SettlementCommand.RemovePlayer -> {
                            editable(old)
                            val player=old.players.firstOrNull { it.userId==command.userId }
                                ?: return@write ActionReceipt(0,old.version)
                            state(player.paid==0L,"У игрока указана оплата. Сначала исправь её в управлении игроками")
                            sqlUpdate(c,"DELETE FROM training_players WHERE group_id=? AND training_id=? AND user_id=?",a.groupId,trainingId,command.userId)
                        }
                        is SettlementCommand.EditTraining -> {
                            stale(old.version, command.version); editable(old)
                            validateDetails(command.title, command.date, command.startTime)
                            sqlUpdate(c, "UPDATE trainings SET title=?,played_on=?,starts_at=? WHERE group_id=? AND id=?", command.title, command.date, command.startTime, a.groupId, trainingId)
                        }
                        is SettlementCommand.ChangeAttendance -> {
                            editable(old)
                            allowed(command.userId == a.userId || canManageTraining(c,a,old), "Можно менять только свои время и оплату")
                            known(c, a.groupId, command.userId)
                            val row = old.players.firstOrNull { it.userId == command.userId }
                                ?: Attendance(command.userId, false, ordinal = (old.players.maxOfOrNull { it.ordinal } ?: -1) + 1)
                            val changed = changeAttendance(row, command)
                            if (changed==row) return@write ActionReceipt(0,old.version)
                            if(!changed.playing && changed.paid==0L)
                                sqlUpdate(c,"DELETE FROM training_players WHERE group_id=? AND training_id=? AND user_id=?",a.groupId,trainingId,changed.userId)
                            else sqlUpdate(c, """INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal)
                                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET
                                playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,guest_count=excluded.guest_count,paid=excluded.paid""",
                                a.groupId, trainingId, changed.userId, changed.playing, changed.minutes, changed.guestMinutes, changed.guestCount, changed.paid, changed.ordinal)
                            validateDraftTraining(readTraining(c, a.groupId, trainingId).calculation())
                        }
                        is SettlementCommand.SaveAttendance -> {
                            editable(old)
                            allowed(command.userId == a.userId || canManageTraining(c,a,old), "Можно менять только свои время и оплату")
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
                            if(!row.playing && row.paid==0L)
                                sqlUpdate(c,"DELETE FROM training_players WHERE group_id=? AND training_id=? AND user_id=?",a.groupId,trainingId,row.userId)
                            else sqlUpdate(c,"""INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal)
                                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET
                                playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,guest_count=excluded.guest_count,paid=excluded.paid""",
                                a.groupId,trainingId,row.userId,row.playing,row.minutes,row.guestMinutes,row.guestCount,row.paid,row.ordinal)
                            validateDraftTraining(readTraining(c,a.groupId,trainingId).calculation())
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
                            TrainingLifecycle.requireTransition(old.phase,TrainingPhase.OPEN)
                            state(old.phase==TrainingPhase.CLOSED,"Открыть заново можно учтённую тренировку")
                            entries = reverseTraining(c,a.groupId,trainingId)
                            sqlUpdate(c, "UPDATE trainings SET status='OPEN',applied_version=0 WHERE group_id=? AND id=?", a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE training_players SET applied_playing=0 WHERE group_id=? AND training_id=?", a.groupId, trainingId)
                        }
                        is SettlementCommand.CancelTraining -> {
                            stale(old.version, command.version)
                            TrainingLifecycle.requireTransition(old.phase,TrainingPhase.CANCELLED)
                            entries = reverseTraining(c, a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE trainings SET status='CANCELLED',applied_version=0 WHERE group_id=? AND id=?", a.groupId, trainingId)
                            sqlUpdate(c, "UPDATE training_players SET applied_playing=0 WHERE group_id=? AND training_id=?", a.groupId, trainingId)
                        }
                        is SettlementCommand.RestoreTraining -> {
                            stale(old.version, command.version)
                            TrainingLifecycle.requireTransition(old.phase,TrainingPhase.OPEN)
                            state(old.phase==TrainingPhase.CANCELLED,"Восстановить можно только отменённую тренировку")
                            // Cancellation already reversed the ledger. Keep the saved input for explicit accounting.
                            sqlUpdate(c, "UPDATE trainings SET status='OPEN' WHERE group_id=? AND id=?", a.groupId, trainingId)
                        }
                        else -> error("Unhandled training command")
                    }
                    sqlUpdate(c, "UPDATE trainings SET version=? WHERE group_id=? AND id=?", version, a.groupId, trainingId)
                }
                refreshAttendance(c,a.groupId)
                after = json.encodeToString(readTraining(c, a.groupId, trainingId))
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
        if(command is SettlementCommand.SendOtherPayment || command is SettlementCommand.RecordAdminPayment)
            availablePayments(c,database,a.groupId)
        ActionReceipt(actionId, version)
    }

    fun previewAttendance(row: Attendance, change: AttendanceChange, value: Long=0): Attendance =
        changeAttendance(row,SettlementCommand.ChangeAttendance("input",row.userId,change,value))
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
    private fun editable(t: TrainingRecord) = TrainingLifecycle.requireOpen(t)

    fun training(a: Access, id: String): TrainingRecord = database.read { known(it, a.groupId, a.userId); readTraining(it, a.groupId, id) }
    fun trainings(a: Access, page: Int = 0, mine: Boolean = false, unfinished: Boolean = false): Page<TrainingRecord> = database.read { c ->
        known(c, a.groupId, a.userId)
        val condition = "FROM trainings t WHERE t.group_id=?" + (if (mine) " AND ((t.created_by=? AND t.status='OPEN') OR EXISTS (SELECT 1 FROM training_players p WHERE p.group_id=t.group_id AND p.training_id=t.id AND p.user_id=? AND (p.playing=1 OR p.applied_playing=1 OR p.paid>0)))" else "") + (if (unfinished) " AND t.status='OPEN'" else "")
        val args = if (mine) arrayOf<Any>(a.groupId, a.userId, a.userId) else arrayOf<Any>(a.groupId)
        val total = count(c, "SELECT COUNT(*) $condition", *args)
        val index = pageIndex(page, total)
        val ids = sqlQuery(c, "SELECT t.id $condition ORDER BY t.played_on DESC,t.starts_at DESC,t.id LIMIT 8 OFFSET ?", *args, index * 8) { it.getString(1) }
        Page(ids.map { readTraining(c, a.groupId, it) }, total, index)
    }
    private fun recentSimilar(c:Connection,group:Long,from:Long,to:Long,amount:Long,exclude:String=""):List<String> =
        sqlQuery(c,"""SELECT id FROM transfers WHERE group_id=? AND from_user=? AND to_user=? AND amount=?
            AND id<>? AND status<>'CANCELLED' AND julianday(created_at)>=julianday(?)
            ORDER BY created_at DESC,id LIMIT 8""",group,from,to,amount,exclude,clock.instant().minusSeconds(86400).toString()) { it.getString(1) }
    private fun paymentDate(c:Connection,group:Long)=LocalDate.now(clock.withZone(ZoneId.of(
        sqlQuery(c,"SELECT time_zone FROM groups WHERE id=?",group) { it.getString(1) }.single()))).toString()
    fun similarPayments(a:Access,from:Long,to:Long,amount:Long):List<String> = database.read { c ->
        known(c,a.groupId,a.userId);known(c,a.groupId,from);known(c,a.groupId,to)
        allowed(a.userId==from || admin(c,a),"Можно записывать только свою отправку")
        recentSimilar(c,a.groupId,from,to,amount)
    }
    fun pendingPaymentCount(a:Access):Int = database.read { c ->
        known(c,a.groupId,a.userId)
        count(c,"SELECT COUNT(*) FROM transfers WHERE group_id=? AND to_user=? AND status='REVIEW'",a.groupId,a.userId)
    }
    fun administrativePayment(a:Access,id:String):Boolean = database.read { c ->
        known(c,a.groupId,a.userId)
        count(c,"SELECT COUNT(*) FROM actions WHERE group_id=? AND transfer_id=? AND kind='RecordAdminPayment'",a.groupId,id)>0
    }
    fun financeBalances(a:Access,page:Int=0):Page<AccountBalance> = database.read { c ->
        known(c,a.groupId,a.userId)
        val balances=database.balances(c,a.groupId)
        val all=sqlQuery(c,"""SELECT u.*,gu.present,gu.attendance_count,gu.has_played FROM group_users gu
            JOIN users u ON u.id=gu.user_id WHERE gu.group_id=? AND u.is_bot=0""",a.groupId) {
            AccountBalance(readAccount(it),balances[it.getLong("id")] ?: 0,it.getInt("attendance_count"),it.getBoolean("present"),it.getBoolean("has_played"))
        }.filter { it.balance!=0L || it.hasPlayed }.sortedWith(compareBy<AccountBalance> { it.balance==0L }
            .thenByDescending { it.balance }.thenBy { it.account.name.lowercase() }.thenBy { it.account.id })
        val index=page.coerceIn(0,maxOf(0,(all.size-1)/5))
        Page(all.drop(index*5).take(5),all.size,index,5)
    }
    fun rosterIds(a:Access,presentOnly:Boolean=false):List<Long> = database.read { c ->
        known(c,a.groupId,a.userId)
        sqlQuery(c,"""SELECT u.id FROM group_users gu JOIN users u ON u.id=gu.user_id WHERE gu.group_id=? AND u.is_bot=0 AND (?=0 OR gu.present=1)
            ORDER BY gu.attendance_count DESC,u.first_name COLLATE NOCASE,u.id""",a.groupId,presentOnly) { it.getLong(1) }
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
    fun paymentSuggestions(a:Access,incoming:Boolean=false,page:Int=0):Page<SuggestedTransfer> = database.read { c ->
        known(c,a.groupId,a.userId)
        val all=availablePayments(c,database,a.groupId).filter { if(incoming) it.to.value==a.userId.toString() else it.from.value==a.userId.toString() }
        val size=if(incoming) 5 else 3
        val index=page.coerceIn(0,maxOf(0,(all.size-1)/size))
        Page(all.drop(index*size).take(size),all.size,index,size)
    }
    fun financePayments(a:Access,page:Int=0,incomingOnly:Boolean=false):Page<MoneyTransfer> = database.read { c ->
        known(c,a.groupId,a.userId)
        val condition="FROM transfers WHERE group_id=? AND "+if(incomingOnly) "to_user=? AND status='REVIEW'" else "(from_user=? OR to_user=?) AND status IN ('ACTIVE','REVIEW')"
        val args=if(incomingOnly) arrayOf<Any>(a.groupId,a.userId) else arrayOf<Any>(a.groupId,a.userId,a.userId)
        val total=count(c,"SELECT COUNT(*) $condition",*args);val size=if(incomingOnly) 3 else 5
        val index=page.coerceIn(0,maxOf(0,(total-1)/size))
        val ids=sqlQuery(c,"SELECT id $condition ORDER BY occurred_on DESC,created_at DESC,id LIMIT ? OFFSET ?",*args,size,index*size) { it.getString(1) }
        Page(ids.map { transfer(c,a.groupId,it) },total,index,size)
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
