CREATE TABLE tg_identity (singleton INTEGER PRIMARY KEY CHECK(singleton=1), bot_id INTEGER NOT NULL, username TEXT NOT NULL) STRICT;
CREATE TABLE tg_groups (group_id TEXT PRIMARY KEY, chat_id INTEGER NOT NULL UNIQUE, title TEXT NOT NULL,
    FOREIGN KEY(group_id) REFERENCES app_groups(group_id)) STRICT;
CREATE TABLE tg_users (user_id INTEGER PRIMARY KEY, display_name TEXT NOT NULL) STRICT;
CREATE TABLE tg_sessions (user_id INTEGER PRIMARY KEY, group_id TEXT, panel_id INTEGER, input_json TEXT,
    FOREIGN KEY(group_id) REFERENCES tg_groups(group_id)) STRICT;
CREATE TABLE tg_actions (token TEXT PRIMARY KEY, user_id INTEGER NOT NULL, group_id TEXT NOT NULL, action_json TEXT NOT NULL,
    FOREIGN KEY(group_id) REFERENCES tg_groups(group_id)) STRICT;
CREATE TABLE tg_links (token TEXT PRIMARY KEY, group_id TEXT NOT NULL, action_json TEXT NOT NULL,
    UNIQUE(group_id,action_json), FOREIGN KEY(group_id) REFERENCES tg_groups(group_id)) STRICT;
CREATE TABLE tg_updates (update_id INTEGER PRIMARY KEY, completed INTEGER NOT NULL CHECK(completed IN (0,1))) STRICT;
CREATE TABLE tg_plans (update_id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, group_id TEXT NOT NULL, token TEXT NOT NULL, action_json TEXT NOT NULL,
    FOREIGN KEY(group_id) REFERENCES tg_groups(group_id)) STRICT;
CREATE TABLE tg_deliveries (delivery_key TEXT PRIMARY KEY, group_id TEXT, chat_id INTEGER NOT NULL, message_id INTEGER,
    status TEXT NOT NULL CHECK(status IN ('SENDING','SENT','UNKNOWN','FAILED','BLOCKED','RETRY')),
    FOREIGN KEY(group_id) REFERENCES tg_groups(group_id)) STRICT;
CREATE TABLE tg_recoveries (token TEXT PRIMARY KEY, delivery_key TEXT NOT NULL) STRICT;
