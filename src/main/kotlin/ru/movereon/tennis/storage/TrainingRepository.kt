package ru.movereon.tennis.storage

import java.sql.Connection
import ru.movereon.tennis.application.*
import ru.movereon.tennis.core.*

internal fun readTraining(c: Connection, group: Long, id: String): TrainingRecord {
    val players = sqlQuery(c, "SELECT * FROM training_players WHERE group_id=? AND training_id=? ORDER BY ordinal", group, id) {
        Attendance(it.getLong("user_id"), it.getBoolean("playing"), it.getLong("minutes"), it.getLong("guest_minutes"), it.getLong("paid"), it.getInt("ordinal"), it.getBoolean("applied_playing"),it.getInt("guest_count"))
    }
    return sqlQuery(c, "SELECT * FROM trainings WHERE group_id=? AND id=?", group, id) {
        TrainingRecord(group, id, it.getString("title"), it.getString("played_on"), it.getString("starts_at"), TrainingPhase.valueOf(it.getString("status")), it.getLong("version"), it.getLong("applied_version"), it.getLong("created_by"), players)
    }.singleOrNull() ?: throw AccountingException(ErrorCode.INVALID_INPUT,"Тренировка не найдена в этой группе")
}
