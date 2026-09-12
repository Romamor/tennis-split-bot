package ru.movereon.tennis.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.math.BigInteger
import ru.movereon.tennis.core.*

/** One normalized database. Pre-release legacy schemas are intentionally not migrated. */
class Database(path: Path, private val trace:((String)->Unit)?=null, private val readOnly:Boolean=false) {
    val readTransactions=java.util.concurrent.atomic.AtomicLong()
    val writeTransactions=java.util.concurrent.atomic.AtomicLong()
    val path=path.toAbsolutePath().normalize()
    init {
        if(readOnly) {
            require(Files.isRegularFile(this.path)) { "Файл базы не найден" }
            read { c ->
                require(sqlQuery(c,"PRAGMA application_id") { it.getInt(1) }.single()==APPLICATION_ID &&
                    sqlQuery(c,"PRAGMA user_version") { it.getInt(1) }.single() in 1..5) { "Неизвестный формат базы бота" }
            }
        } else {
            Files.createDirectories(this.path.parent)
            write { c ->
                val app=sqlQuery(c,"PRAGMA application_id") { it.getInt(1) }.single()
                val version=sqlQuery(c,"PRAGMA user_version") { it.getInt(1) }.single()
                if(version==0) {
                    require(app==0 && sqlQuery(c,"SELECT COUNT(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'") { it.getInt(1) }.single()==0) { "Файл занят другой базой" }
                    val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema.sql")).bufferedReader().use { it.readText() }
                    c.createStatement().use { s -> ddl.split(';').filter { it.isNotBlank() }.forEach { s.execute(it) };s.execute("PRAGMA application_id=$APPLICATION_ID");s.execute("PRAGMA user_version=5") }
                } else {
                    require(app==APPLICATION_ID && version in 1..5) { "Нужен отдельный файл новой базы. Старая тестовая база не изменена." }
                    if(version==1) {
                        c.createStatement().use { it.execute("ALTER TABLE group_users ADD COLUMN attendance_count INTEGER NOT NULL DEFAULT 0 CHECK(attendance_count>=0)") }
                        c.createStatement().use { it.execute("ALTER TABLE group_users ADD COLUMN has_played INTEGER NOT NULL DEFAULT 0 CHECK(has_played IN (0,1))") }
                        refreshAttendance(c)
                        c.createStatement().use { it.execute("PRAGMA user_version=2") }
                    }
                    if(version<=2) migrateParticipation(c)
                    if(version<=3) migrateUnpin(c)
                    if(version<=4) migrateTrainingStates(c,this)
                }
            }
            connect().use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA journal_mode=WAL").close() } }
        }
    }
    private fun migrateUnpin(c:Connection) {
        c.createStatement().use { s ->
            s.execute("ALTER TABLE bot_deliveries ADD COLUMN pin_status_next TEXT NOT NULL DEFAULT 'NONE' CHECK(pin_status_next IN ('NONE','PENDING','SENDING','SENT','FAILED','UNKNOWN','UNPIN_PENDING','UNPIN_SENDING','UNPIN_FAILED','UNPINNED'))")
            s.execute("UPDATE bot_deliveries SET pin_status_next=pin_status")
            s.execute("ALTER TABLE bot_deliveries DROP COLUMN pin_status")
            s.execute("ALTER TABLE bot_deliveries RENAME COLUMN pin_status_next TO pin_status")
            s.execute("ALTER TABLE groups ADD COLUMN default_start_time TEXT NOT NULL DEFAULT '18:30' CHECK(default_start_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(default_start_time,1,2)<='23')")
            s.execute("PRAGMA user_version=4")
        }
    }
    private fun migrateParticipation(c:Connection) {
        val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema.sql")).bufferedReader().use { it.readText() }
        val table=ddl.substringAfter("CREATE TABLE training_players (").substringBefore(") STRICT;")
        c.createStatement().use { s ->
            s.execute("CREATE TABLE training_players_next ($table) STRICT")
            s.execute("""INSERT INTO training_players_next(group_id,training_id,user_id,playing,applied_playing,minutes,guest_minutes,guest_count,paid,ordinal)
                SELECT group_id,training_id,user_id,playing,applied_playing,minutes,guest_minutes,CASE WHEN guest_minutes>0 THEN 1 ELSE 0 END,paid,ordinal FROM training_players""")
            s.execute("DROP TABLE training_players")
            s.execute("ALTER TABLE training_players_next RENAME TO training_players")
            s.execute("CREATE INDEX player_trainings ON training_players(group_id,user_id,playing,training_id)")
            s.execute("ALTER TABLE bot_sessions ADD COLUMN panel_json TEXT")
            s.execute("ALTER TABLE bot_deliveries ADD COLUMN display_page INTEGER NOT NULL DEFAULT 0 CHECK(display_page>=0)")
            s.execute("ALTER TABLE bot_deliveries ADD COLUMN pin_status TEXT NOT NULL DEFAULT 'NONE' CHECK(pin_status IN ('NONE','PENDING','SENDING','SENT','FAILED','UNKNOWN'))")
            s.execute("PRAGMA user_version=3")
        }
    }
    private fun connect(): Connection = DriverManager.getConnection(if(readOnly) "jdbc:sqlite:${path.toUri()}?mode=ro" else "jdbc:sqlite:$path").also { c ->
        c.createStatement().use { s -> s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");s.execute(if(readOnly) "PRAGMA query_only=ON" else "PRAGMA synchronous=FULL") }
    }
    fun <T> read(block:(Connection)->T):T=transaction(false,block)
    fun <T> write(block:(Connection)->T):T { check(!readOnly) { "База открыта только для чтения" };return transaction(true,block) }
    private fun <T> transaction(write:Boolean,block:(Connection)->T):T=connect().use { c ->
        if(write) writeTransactions.incrementAndGet() else readTransactions.incrementAndGet()
        val previous=sqlTrace.get();sqlTrace.set(trace)
        c.createStatement().use { it.execute(if(write) "BEGIN IMMEDIATE" else "BEGIN") }
        try { block(c).also { c.createStatement().use { it.execute("COMMIT") } } }
        catch(t:Throwable) { try { c.createStatement().use { it.execute("ROLLBACK") } } catch(r:Throwable) { t.addSuppressed(r) };throw t }
        finally { sqlTrace.set(previous) }
    }
    fun balances(groupId:Long):Map<Long,Long> = read { balances(it,groupId) }
    internal fun balances(c:Connection,groupId:Long):Map<Long,Long> = sums(c,
        "SELECT user_id,amount FROM balance_entries WHERE group_id=?",groupId)
    internal fun trainingEffect(c:Connection,groupId:Long,id:String):Map<Long,Long> = sums(c,
        "SELECT e.user_id,e.amount FROM balance_entries e JOIN actions a ON a.id=e.action_id WHERE a.group_id=? AND a.training_id=?",groupId,id)
    private fun sums(c:Connection,sql:String,vararg params:Any?):Map<Long,Long> {
        val sums=linkedMapOf<Long,BigInteger>()
        sqlQuery(c,sql,*params) { r -> val id=r.getLong(1);sums[id]=(sums[id]?:BigInteger.ZERO)+r.getLong(2).toBigInteger() }
        return sums.mapValues { (_,v)->v.toAmount() }
    }
    fun verify():String = read { c ->
        check(sqlQuery(c,"PRAGMA integrity_check") { it.getString(1) }==listOf("ok")) { "Повреждена SQLite" }
        check(sqlQuery(c,"PRAGMA foreign_key_check") { it.getString(1) }.isEmpty()) { "Нарушены связи" }
        val totals=mutableMapOf<Long,BigInteger>()
        sqlQuery(c,"SELECT action_id,amount FROM balance_entries") { r -> val id=r.getLong(1);totals[id]=(totals[id]?:BigInteger.ZERO)+r.getLong(2).toBigInteger() }
        check(totals.values.all { it==BigInteger.ZERO }) { "Несбалансированная операция" }
        val groups=sqlQuery(c,"SELECT id FROM groups") { it.getLong(1) }
        groups.forEach { validateBalances(balances(c,it).mapKeys { (id,_)->ParticipantId(id.toString()) }) }
        "Схема ${sqlQuery(c,"PRAGMA user_version") { it.getInt(1) }.single()} (новая модель): ${groups.size} групп, ${totals.size} денежных операций; целостность в порядке"
    }
    /** SQLite Online Backup includes committed WAL pages without migrating or changing the source. */
    fun backup(destination:Path):Path {
        val target=destination.toAbsolutePath().normalize();require(!Files.exists(target)) { "Копия уже существует" }
        Files.createDirectories(target.parent)
        val privateFile=java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        val temporary=Files.createTempFile(target.parent,".tennis-backup-",".sqlite",privateFile)
        try {
            connect().use { c ->
                val result=(c as org.sqlite.SQLiteConnection).database.backup("main",temporary.toString(),null)
                check(result==0) { "Не удалось завершить резервную копию: SQLite $result" }
            }
            Database(temporary,readOnly=true).verify()
            Files.move(temporary,target)
            return target
        } finally { Files.deleteIfExists(temporary) }
    }
    companion object { const val APPLICATION_ID=0x54534e32 }
}

internal fun sqlUpdate(c:Connection,sql:String,vararg params:Any?):Int = c.prepareStatement(sql).use { s ->
    sqlTrace.get()?.invoke(sql)
    params.forEachIndexed { i,p->s.setObject(i+1,p) };s.executeUpdate()
}
internal fun <T> sqlQuery(c:Connection,sql:String,vararg params:Any?,map:(ResultSet)->T):List<T> = c.prepareStatement(sql).use { s ->
    sqlTrace.get()?.invoke(sql)
    params.forEachIndexed { i,p->s.setObject(i+1,p) };s.executeQuery().use { r -> buildList { while(r.next())add(map(r)) } }
}

private val sqlTrace=ThreadLocal<((String)->Unit)?>()
internal fun refreshAttendance(c:Connection,group:Long?=null) {
    val where=if(group==null) "" else " WHERE group_id=?"
    val params=if(group==null) emptyArray<Any>() else arrayOf<Any>(group)
    sqlUpdate(c,"""UPDATE group_users SET attendance_count=(SELECT COUNT(*) FROM training_players p
        JOIN trainings t ON t.group_id=p.group_id AND t.id=p.training_id
        WHERE p.group_id=group_users.group_id AND p.user_id=group_users.user_id
        AND p.applied_playing=1 AND t.status='CLOSED'),
        has_played=EXISTS(SELECT 1 FROM training_players p JOIN trainings t ON t.group_id=p.group_id AND t.id=p.training_id
        WHERE p.group_id=group_users.group_id AND p.user_id=group_users.user_id AND t.status<>'CANCELLED'
        AND (p.playing=1 OR p.applied_playing=1))$where""",*params)
}
