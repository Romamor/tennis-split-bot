package ru.movereon.tennis.application

import java.math.BigInteger
import java.sql.Connection
import ru.movereon.tennis.core.toAmount
import ru.movereon.tennis.storage.*

/** Fold every posted entry into its source before filtering and paging; never discard money by status. */
internal fun readBalanceHistory(c:Connection,group:Long,user:Long,page:Int):BalanceHistory {
    data class Source(val trainingId:String?,val transferId:String?)
    val changes=linkedMapOf<Source,BigInteger>()
    val latest=mutableMapOf<Source,Long>()
    sqlEach(c,"""SELECT b.action_id,b.amount,a.training_id,a.transfer_id
        FROM balance_entries b JOIN actions a ON a.id=b.action_id AND a.group_id=b.group_id
        WHERE b.group_id=? AND b.user_id=? ORDER BY b.action_id DESC,b.entry_index""",group,user) { r ->
        val source=Source(r.getString("training_id"),r.getString("transfer_id"))
        check((source.trainingId!=null) xor (source.transferId!=null)) { "Balance entry must have exactly one source" }
        latest.putIfAbsent(source,r.getLong("action_id"))
        changes[source]=(changes[source] ?: BigInteger.ZERO)+r.getLong("amount").toBigInteger()
    }
    val balance=changes.values.fold(BigInteger.ZERO,BigInteger::add).toAmount()
    val amounts=changes.filterValues { it.signum()!=0 }.mapKeys { latest.getValue(it.key) }
    val ids=amounts.keys.toList()
    val size=15
    val selectedPage=ids.page(page,size)
    val selected=selectedPage.items
    val items=if(selected.isEmpty()) emptyList() else sqlQuery(c,"""SELECT a.id,a.kind,a.occurred_at,
        a.training_id,t.title,t.played_on,a.transfer_id,m.from_user,m.to_user
        FROM actions a LEFT JOIN trainings t ON t.group_id=a.group_id AND t.id=a.training_id
        LEFT JOIN transfers m ON m.group_id=a.group_id AND m.id=a.transfer_id
        WHERE a.group_id=? AND a.id IN (${selected.joinToString(",") { "?" }}) ORDER BY a.id DESC""",
        group,*selected.toTypedArray()) { r ->
        BalanceHistoryEntry(r.getLong("id"),r.getString("kind"),r.getString("occurred_at"),amounts.getValue(r.getLong("id")),
            r.getString("training_id"),r.getString("title"),r.getString("transfer_id"),
            r.getString("from_user")?.toLong(),r.getString("to_user")?.toLong(),r.getString("played_on"))
    }
    return BalanceHistory(balance,Page(items,ids.size,selectedPage.index,size))
}
