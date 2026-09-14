package ru.movereon.tennis.storage

import java.sql.Connection
import ru.movereon.tennis.application.Access

internal fun isPresentGroupMember(c:Connection,a:Access)=sqlQuery(c,
    "SELECT EXISTS(SELECT 1 FROM group_users WHERE group_id=? AND user_id=? AND present=1)",a.groupId,a.userId) { it.getBoolean(1) }.single()
internal fun isGroupAdmin(c:Connection,a:Access)=a.telegramAdmin || sqlQuery(c,
    "SELECT EXISTS(SELECT 1 FROM group_admins WHERE group_id=? AND user_id=?)",a.groupId,a.userId) { it.getBoolean(1) }.single()
