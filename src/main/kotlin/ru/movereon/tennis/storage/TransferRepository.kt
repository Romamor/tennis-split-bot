package ru.movereon.tennis.storage

import java.sql.Connection
import java.sql.ResultSet
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

internal fun readTransfer(c: Connection, group: Long, id: String): MoneyTransfer = sqlQuery(c,"SELECT * FROM transfers WHERE group_id=? AND id=?",group,id,map=::transferRow)
        .singleOrNull() ?: throw AccountingException(ErrorCode.INVALID_INPUT,"Перевод не найден в этой группе")
internal fun transferRow(r:ResultSet)=MoneyTransfer(r.getLong("group_id"),r.getString("id"),r.getLong("from_user"),r.getLong("to_user"),r.getLong("amount"),r.getString("occurred_on"),r.getString("note"),PaymentStatus.valueOf(r.getString("status")),r.getLong("version"),r.getString("reviewer")?.toLong(),r.getString("review_party")?.toLong(),r.getLong("created_by"))

/** Called inside the command transaction after authorization and input validation. */
internal fun insertTransfer(c:Connection,group:Long,id:String,from:Long,to:Long,amount:Long,date:String,
    creator:Long,createdAt:String,status:PaymentStatus,note:String="",reviewer:Long?=null,reviewParty:Long?=null):MoneyTransfer {
    sqlUpdate(c,"""INSERT INTO transfers(group_id,id,from_user,to_user,amount,occurred_on,note,status,version,reviewer,review_party,created_by,created_at)
        VALUES(?,?,?,?,?,?,?,?,1,?,?,?,?)""",group,id,from,to,amount,date,note,status.name,reviewer,reviewParty,creator,createdAt)
    return readTransfer(c,group,id)
}
