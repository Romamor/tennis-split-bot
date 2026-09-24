package ru.movereon.tennis.storage

import java.sql.Connection
import java.sql.ResultSet
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

internal fun readPollRow(r:ResultSet)=TrainingPoll(r.getLong("group_id"),r.getString("id"),r.getString("title"),r.getString("played_on"),r.getString("starts_at"),r.getString("decline_label"),r.getLong("created_by"),r.getString("telegram_id"),r.getString("message_id")?.toLong(),r.getString("status"),r.getBoolean("stopped"),r.getString("closed_by")?.toLong(),r.getString("training_id"),r.getString("photo_file_id"))
internal fun readPoll(c:Connection,group:Long,id:String)=
    sqlQuery(c,"SELECT * FROM training_polls WHERE group_id=? AND id=?",group,id,map=::readPollRow).singleOrNull()
        ?: throw AccountingException(ErrorCode.INVALID_INPUT,"Опрос не найден в этой группе")

/** Filter and paginate before mapping rows; the caller supplies the verified role once. */
internal fun readManagedPolls(c:Connection,a:Access,admin:Boolean,page:Int):Page<TrainingPoll> {
    val condition="FROM training_polls WHERE group_id=? AND status<>'CLOSED'"+if(admin) "" else " AND created_by=?"
    val parameters=if(admin) arrayOf<Any>(a.groupId) else arrayOf<Any>(a.groupId,a.userId)
    val total=sqlQuery(c,"SELECT COUNT(*) $condition",*parameters) { it.getInt(1) }.single()
    val index=page.coerceIn(0,maxOf(0,(total-1)/5))
    val rows=sqlQuery(c,"SELECT * $condition ORDER BY created_at DESC,id LIMIT 5 OFFSET ?",*parameters,index*5,map=::readPollRow)
    return Page(rows,total,index,5)
}
