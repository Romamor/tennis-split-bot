package ru.movereon.tennis.storage

import java.sql.Connection

/** Applied once, inside the opening transaction, before serving Telegram events. */
internal fun migratePersonalDefaults(c:Connection) {
    c.createStatement().use { s ->
        s.execute("ALTER TABLE users ADD COLUMN training_title TEXT NOT NULL DEFAULT 'Теннис' CHECK(length(training_title) BETWEEN 1 AND 100 AND training_title=trim(training_title))")
        s.execute("ALTER TABLE users ADD COLUMN training_time TEXT NOT NULL DEFAULT '18:30' CHECK(training_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(training_time,1,2)<='23')")
        s.execute("ALTER TABLE groups DROP COLUMN default_start_time")
        s.execute("ALTER TABLE bot_buttons ADD COLUMN group_next INTEGER REFERENCES groups(id) ON UPDATE CASCADE")
        s.execute("UPDATE bot_buttons SET group_next=group_id")
        s.execute("ALTER TABLE bot_buttons DROP COLUMN group_id")
        s.execute("ALTER TABLE bot_buttons RENAME COLUMN group_next TO group_id")
        s.execute("""UPDATE bot_sessions SET group_id=NULL,input_json=json_set(input_json,
            '$.group',0,'$.kind',CASE WHEN json_extract(input_json,'$.kind')='ready' THEN 'group' ELSE json_extract(input_json,'$.kind') END,
            '$.origin',json('{"kind":"menu","group":0}'))
            WHERE chat_id>0 AND json_valid(input_json) AND COALESCE(json_extract(input_json,'$.training'),'')=''
            AND json_extract(input_json,'$.group')<0 AND json_extract(input_json,'$.kind') IN ('title','date','time','ready')""")
        s.execute("""UPDATE bot_events SET group_id=NULL,plan_json=json_set(plan_json,
            '$.screen.group',0,'$.form.group',0,'$.form.kind',CASE WHEN json_extract(plan_json,'$.form.kind')='ready' THEN 'group' ELSE json_extract(plan_json,'$.form.kind') END,
            '$.form.origin',json('{"kind":"menu","group":0}'))
            WHERE completed=0 AND json_valid(plan_json) AND json_extract(plan_json,'$.command') IS NULL
            AND COALESCE(json_extract(plan_json,'$.form.training'),'')='' AND json_extract(plan_json,'$.form.group')<0
            AND json_extract(plan_json,'$.form.kind') IN ('title','date','time','ready')""")
        s.execute("""UPDATE actions SET delivered_at=NULL WHERE id IN (
            SELECT MAX(a.id) FROM actions a JOIN bot_deliveries d
            ON d.delivery_key='training:' || a.group_id || ':' || a.training_id
            WHERE d.status='SENT' AND d.message_id IS NOT NULL AND a.needs_delivery=1
            GROUP BY a.group_id,a.training_id)""")
        s.execute("PRAGMA user_version=6")
    }
}
internal fun migrateUnpin(c:Connection) {
    c.createStatement().use { s ->
        s.execute("ALTER TABLE bot_deliveries ADD COLUMN pin_status_next TEXT NOT NULL DEFAULT 'NONE' CHECK(pin_status_next IN ('NONE','PENDING','SENDING','SENT','FAILED','UNKNOWN','UNPIN_PENDING','UNPIN_SENDING','UNPIN_FAILED','UNPINNED'))")
        s.execute("UPDATE bot_deliveries SET pin_status_next=pin_status")
        s.execute("ALTER TABLE bot_deliveries DROP COLUMN pin_status")
        s.execute("ALTER TABLE bot_deliveries RENAME COLUMN pin_status_next TO pin_status")
        s.execute("ALTER TABLE groups ADD COLUMN default_start_time TEXT NOT NULL DEFAULT '18:30' CHECK(default_start_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(default_start_time,1,2)<='23')")
        s.execute("PRAGMA user_version=4")
    }
}
internal fun migrateParticipation(c:Connection) {
    val ddl=requireNotNull(Database::class.java.getResourceAsStream("/db/schema.sql")).bufferedReader().use { it.readText() }
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

internal fun migratePolls(c:Connection) {
    val ddl=requireNotNull(Database::class.java.getResourceAsStream("/db/schema.sql")).bufferedReader().use { "CREATE TABLE training_polls"+it.readText().substringAfter("CREATE TABLE training_polls") }
    c.createStatement().use { s ->
        s.execute("ALTER TABLE groups ADD COLUMN polls_enabled INTEGER NOT NULL DEFAULT 0 CHECK(polls_enabled IN (0,1))")
        ddl.split(';').filter { it.isNotBlank() }.forEach { s.execute(it) }
        s.execute("PRAGMA user_version=7")
    }
}
