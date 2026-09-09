package ru.movereon.tennis.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.math.BigInteger
import ru.movereon.tennis.core.*

/** One normalized database. Pre-release legacy schemas are intentionally not migrated. */
class Database(path: Path) {
    val path=path.toAbsolutePath().normalize()
    init {
        Files.createDirectories(this.path.parent)
        write { c ->
            val app=sqlQuery(c,"PRAGMA application_id") { it.getInt(1) }.single()
            val version=sqlQuery(c,"PRAGMA user_version") { it.getInt(1) }.single()
            if(version==0) {
                require(app==0 && sqlQuery(c,"SELECT COUNT(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'") { it.getInt(1) }.single()==0) { "Файл занят другой базой" }
                val ddl=requireNotNull(javaClass.getResourceAsStream("/db/schema.sql")).bufferedReader().use { it.readText() }
                c.createStatement().use { s -> ddl.split(';').filter { it.isNotBlank() }.forEach { s.execute(it) };s.execute("PRAGMA application_id=$APPLICATION_ID");s.execute("PRAGMA user_version=1") }
            } else require(app==APPLICATION_ID && version==1) { "Нужен отдельный файл новой базы. Старая тестовая база не изменена." }
        }
        connect().use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA journal_mode=WAL").close() } }
    }
    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
        c.createStatement().use { s -> s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL") }
    }
    fun <T> read(block:(Connection)->T):T=transaction(false,block)
    fun <T> write(block:(Connection)->T):T=transaction(true,block)
    private fun <T> transaction(write:Boolean,block:(Connection)->T):T=connect().use { c ->
        c.createStatement().use { it.execute(if(write) "BEGIN IMMEDIATE" else "BEGIN") }
        try { block(c).also { c.createStatement().use { it.execute("COMMIT") } } }
        catch(t:Throwable) { try { c.createStatement().use { it.execute("ROLLBACK") } } catch(r:Throwable) { t.addSuppressed(r) };throw t }
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
        "Схема 1 (новая модель): ${groups.size} групп, ${totals.size} денежных операций; целостность в порядке"
    }
    fun backup(destination:Path):Path {
        val target=destination.toAbsolutePath().normalize();require(!Files.exists(target)) { "Копия уже существует" }
        Files.createDirectories(target.parent);Files.createFile(target)
        connect().use { c -> c.prepareStatement("VACUUM INTO ?").use { it.setString(1,target.toString());it.executeUpdate() } }
        Database(target).verify();return target
    }
    companion object { const val APPLICATION_ID=0x54534e32 }
}

internal fun sqlUpdate(c:Connection,sql:String,vararg params:Any?):Int = c.prepareStatement(sql).use { s ->
    params.forEachIndexed { i,p->s.setObject(i+1,p) };s.executeUpdate()
}
internal fun <T> sqlQuery(c:Connection,sql:String,vararg params:Any?,map:(ResultSet)->T):List<T> = c.prepareStatement(sql).use { s ->
    params.forEachIndexed { i,p->s.setObject(i+1,p) };s.executeQuery().use { r -> buildList { while(r.next())add(map(r)) } }
}
