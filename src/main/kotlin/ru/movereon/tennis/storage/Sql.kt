package ru.movereon.tennis.storage

import java.sql.Connection
import java.sql.ResultSet

internal fun sqlUpdate(c:Connection,sql:String,vararg params:Any?):Int = c.prepareStatement(sql).use { s ->
    sqlTrace.get()?.invoke(sql)
    params.forEachIndexed { i,p->s.setObject(i+1,p) };s.executeUpdate()
}
internal fun <T> sqlQuery(c:Connection,sql:String,vararg params:Any?,map:(ResultSet)->T):List<T> = c.prepareStatement(sql).use { s ->
    sqlTrace.get()?.invoke(sql)
    params.forEachIndexed { i,p->s.setObject(i+1,p) };s.executeQuery().use { r -> buildList { while(r.next())add(map(r)) } }
}

internal val sqlTrace=ThreadLocal<((String)->Unit)?>()
/** Visit large result sets without retaining one object per historical row. */
internal fun sqlEach(c:Connection,sql:String,vararg params:Any?,visit:(ResultSet)->Unit) = c.prepareStatement(sql).use { s ->
    sqlTrace.get()?.invoke(sql)
    params.forEachIndexed { i,p->s.setObject(i+1,p) }
    s.executeQuery().use { r -> while(r.next()) visit(r) }
}
