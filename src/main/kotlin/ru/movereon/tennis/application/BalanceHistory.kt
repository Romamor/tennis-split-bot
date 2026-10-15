package ru.movereon.tennis.application

import java.math.BigInteger
import java.sql.Connection
import ru.movereon.tennis.core.toAmount
import ru.movereon.tennis.storage.*

/** Sum actual entries before paging; reversals stay visible even after their source is cancelled. */
internal fun readBalanceHistory(c:Connection,a:Access,page:Int):BalanceHistory {
    val changes=linkedMapOf<Long,BigInteger>()
    sqlEach(c,"""SELECT action_id,amount FROM balance_entries
        WHERE group_id=? AND user_id=? ORDER BY action_id DESC,entry_index""",a.groupId,a.userId) { r ->
        val id=r.getLong(1)
        changes[id]=(changes[id] ?: BigInteger.ZERO)+r.getLong(2).toBigInteger()
    }
    val balance=changes.values.fold(BigInteger.ZERO,BigInteger::add).toAmount()
    val ids=changes.filterValues { it.signum()!=0 }.keys.toList()
    val size=10
    val index=page.coerceIn(0,maxOf(0,(ids.size-1)/size))
    val selected=ids.drop(index*size).take(size)
    val items=if(selected.isEmpty()) emptyList() else sqlQuery(c,"""SELECT a.id,a.kind,a.occurred_at,
        a.training_id,t.title,a.transfer_id,m.from_user,m.to_user
        FROM actions a LEFT JOIN trainings t ON t.group_id=a.group_id AND t.id=a.training_id
        LEFT JOIN transfers m ON m.group_id=a.group_id AND m.id=a.transfer_id
        WHERE a.group_id=? AND a.id IN (${selected.joinToString(",") { "?" }}) ORDER BY a.id DESC""",
        a.groupId,*selected.toTypedArray()) { r ->
        BalanceHistoryEntry(r.getLong("id"),r.getString("kind"),r.getString("occurred_at"),changes.getValue(r.getLong("id")),
            r.getString("training_id"),r.getString("title"),r.getString("transfer_id"),
            r.getString("from_user")?.toLong(),r.getString("to_user")?.toLong())
    }
    return BalanceHistory(balance,Page(items,ids.size,index,size))
}
