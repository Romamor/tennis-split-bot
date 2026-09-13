package ru.movereon.tennis.application

import ru.movereon.tennis.core.*
import ru.movereon.tennis.storage.*
import java.sql.Connection

/** Pending receipts reserve settlement amounts without changing the posted ledger. */
internal fun availablePayments(c:Connection,database:Database,group:Long):List<SuggestedTransfer> {
    val posted=database.balances(c,group).mapKeys { ParticipantId(it.key.toString()) }
    val pending=sqlQuery(c,"SELECT from_user,to_user,amount FROM transfers WHERE group_id=? AND status='REVIEW'",group) {
        Triple(it.getLong(1),it.getLong(2),it.getLong(3))
    }.flatMap { (from,to,amount) -> listOf(BalanceEntry(ParticipantId(from.toString()),amount),BalanceEntry(ParticipantId(to.toString()),-amount)) }
    return suggestTransfers(applyEntries(posted,pending))
}
