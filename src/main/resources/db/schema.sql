CREATE TABLE users (
    id INTEGER PRIMARY KEY CHECK(id>0),
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL DEFAULT '',
    username TEXT,
    is_bot INTEGER NOT NULL DEFAULT 0 CHECK(is_bot IN (0,1))
) STRICT;
CREATE UNIQUE INDEX single_bot ON users(is_bot) WHERE is_bot=1;
CREATE INDEX user_username ON users(username COLLATE NOCASE);
CREATE TABLE groups (
    id INTEGER PRIMARY KEY CHECK(id<0),
    title TEXT NOT NULL,
    time_zone TEXT NOT NULL
) STRICT;
CREATE TABLE group_users (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON UPDATE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id),
    present INTEGER NOT NULL CHECK(present IN (0,1)),
    attendance_count INTEGER NOT NULL DEFAULT 0 CHECK(attendance_count>=0),
    has_played INTEGER NOT NULL DEFAULT 0 CHECK(has_played IN (0,1)),
    -- Planned group preferences. UI and behavior are not implemented yet.
    is_attending INTEGER NOT NULL DEFAULT 1 CHECK(is_attending IN (0,1)),
    nickname TEXT CHECK(nickname IS NULL OR (length(nickname) BETWEEN 1 AND 64 AND nickname=trim(nickname))),
    PRIMARY KEY(group_id,user_id)
) STRICT;
CREATE TABLE group_admins (
    group_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    granted_by INTEGER NOT NULL REFERENCES users(id),
    granted_at TEXT NOT NULL,
    PRIMARY KEY(group_id,user_id),
    FOREIGN KEY(group_id,user_id) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,granted_by) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE
) STRICT;
CREATE TABLE trainings (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON UPDATE CASCADE,
    id TEXT NOT NULL,
    title TEXT NOT NULL CHECK(length(trim(title)) BETWEEN 1 AND 100),
    played_on TEXT NOT NULL,
    starts_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('OPEN','CLOSED','REVIEW','CANCELLED')),
    version INTEGER NOT NULL CHECK(version>0),
    applied_version INTEGER NOT NULL DEFAULT 0 CHECK(applied_version>=0),
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    PRIMARY KEY(group_id,id),
    FOREIGN KEY(group_id,created_by) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE
) STRICT;
CREATE INDEX training_dates ON trainings(group_id,played_on DESC,id);
CREATE TABLE training_players (
    group_id INTEGER NOT NULL,
    training_id TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    playing INTEGER NOT NULL CHECK(playing IN (0,1)),
    applied_playing INTEGER NOT NULL DEFAULT 0 CHECK(applied_playing IN (0,1)),
    minutes INTEGER NOT NULL DEFAULT 60 CHECK(minutes>0 AND minutes%30=0),
    guest_minutes INTEGER NOT NULL DEFAULT 0 CHECK(guest_minutes>=0 AND guest_minutes%30=0),
    paid INTEGER NOT NULL DEFAULT 0 CHECK(paid>=0),
    ordinal INTEGER NOT NULL CHECK(ordinal>=0),
    PRIMARY KEY(group_id,training_id,user_id),
    UNIQUE(group_id,training_id,ordinal),
    CHECK(playing=1 OR guest_minutes=0),
    FOREIGN KEY(group_id,training_id) REFERENCES trainings(group_id,id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,user_id) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE
) STRICT;
CREATE INDEX player_trainings ON training_players(group_id,user_id,playing,training_id);
CREATE TABLE transfers (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON UPDATE CASCADE,
    id TEXT NOT NULL,
    from_user INTEGER NOT NULL,
    to_user INTEGER NOT NULL,
    amount INTEGER NOT NULL CHECK(amount>0),
    occurred_on TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '' CHECK(length(note)<=300),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVIEW','CANCELLED')),
    version INTEGER NOT NULL CHECK(version>0),
    reviewer INTEGER REFERENCES users(id),
    review_party INTEGER REFERENCES users(id),
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    PRIMARY KEY(group_id,id),
    CHECK(from_user<>to_user),
    CHECK((status='REVIEW')=(reviewer IS NOT NULL)),
    CHECK((status='REVIEW')=(review_party IS NOT NULL)),
    CHECK(review_party IS NULL OR review_party IN (from_user,to_user)),
    FOREIGN KEY(group_id,from_user) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,to_user) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,created_by) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,reviewer) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE
) STRICT;
CREATE INDEX transfer_dates ON transfers(group_id,occurred_on DESC,id);
CREATE TABLE actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL REFERENCES groups(id) ON UPDATE CASCADE,
    request_id TEXT NOT NULL,
    actor_id INTEGER NOT NULL REFERENCES users(id),
    kind TEXT NOT NULL,
    training_id TEXT,
    transfer_id TEXT,
    payload_json TEXT NOT NULL,
    before_json TEXT,
    after_json TEXT NOT NULL,
    result_version INTEGER NOT NULL,
    occurred_at TEXT NOT NULL,
    needs_delivery INTEGER NOT NULL CHECK(needs_delivery IN (0,1)),
    delivered_at TEXT,
    UNIQUE(group_id,request_id),
    UNIQUE(group_id,id),
    CHECK(training_id IS NULL OR transfer_id IS NULL),
    FOREIGN KEY(group_id,training_id) REFERENCES trainings(group_id,id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,transfer_id) REFERENCES transfers(group_id,id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,actor_id) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE
) STRICT;
CREATE INDEX action_history ON actions(group_id,id DESC);
CREATE INDEX training_actions ON actions(group_id,training_id,id);
CREATE INDEX transfer_actions ON actions(group_id,transfer_id,id);
CREATE INDEX pending_actions ON actions(id) WHERE needs_delivery=1 AND delivered_at IS NULL;
CREATE TABLE balance_entries (
    action_id INTEGER NOT NULL,
    group_id INTEGER NOT NULL,
    entry_index INTEGER NOT NULL CHECK(entry_index>=0),
    user_id INTEGER NOT NULL,
    amount INTEGER NOT NULL CHECK(amount>=-9223372036854775807),
    PRIMARY KEY(action_id,entry_index),
    FOREIGN KEY(group_id,action_id) REFERENCES actions(group_id,id) ON UPDATE CASCADE,
    FOREIGN KEY(group_id,user_id) REFERENCES group_users(group_id,user_id) ON UPDATE CASCADE
) STRICT;
CREATE INDEX account_balance ON balance_entries(group_id,user_id,action_id);
CREATE TABLE bot_events (
    update_id INTEGER PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    group_id INTEGER REFERENCES groups(id) ON UPDATE CASCADE,
    plan_json TEXT,
    completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1))
) STRICT;
CREATE TABLE bot_sessions (
    user_id INTEGER NOT NULL REFERENCES users(id),
    chat_id INTEGER NOT NULL,
    group_id INTEGER REFERENCES groups(id) ON UPDATE CASCADE,
    message_id INTEGER,
    ephemeral_id INTEGER,
    input_json TEXT,
    PRIMARY KEY(user_id,chat_id)
) STRICT;
CREATE TABLE bot_buttons (
    token TEXT PRIMARY KEY,
    group_id INTEGER NOT NULL REFERENCES groups(id) ON UPDATE CASCADE,
    owner_id INTEGER REFERENCES users(id),
    scope TEXT NOT NULL,
    permanent INTEGER NOT NULL CHECK(permanent IN (0,1)),
    payload_hash TEXT NOT NULL,
    action_json TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 0 CHECK(active IN (0,1)),
    expires_at INTEGER NOT NULL
) STRICT;
CREATE INDEX button_reuse ON bot_buttons(scope,owner_id,payload_hash);
CREATE INDEX active_buttons ON bot_buttons(scope) WHERE active=1;
CREATE INDEX expired_buttons ON bot_buttons(expires_at) WHERE active=0 AND permanent=0;
CREATE TABLE bot_deliveries (
    delivery_key TEXT PRIMARY KEY,
    group_id INTEGER REFERENCES groups(id) ON UPDATE CASCADE,
    chat_id INTEGER NOT NULL,
    user_id INTEGER REFERENCES users(id),
    message_id INTEGER,
    ephemeral_id INTEGER,
    status TEXT NOT NULL CHECK(status IN ('SENDING','SENT','UNKNOWN','FAILED','BLOCKED','RETRY'))
) STRICT;
