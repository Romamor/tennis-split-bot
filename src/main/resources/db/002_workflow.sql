CREATE TABLE app_groups (
    group_id TEXT PRIMARY KEY,
    time_zone TEXT NOT NULL,
    created_by INTEGER NOT NULL CHECK(created_by > 0)
) STRICT;

CREATE TABLE organizers (
    group_id TEXT NOT NULL,
    user_id INTEGER NOT NULL CHECK(user_id > 0),
    PRIMARY KEY(group_id,user_id),
    FOREIGN KEY(group_id) REFERENCES app_groups(group_id)
) STRICT;

CREATE TABLE participants (
    group_id TEXT NOT NULL,
    participant_id TEXT NOT NULL,
    display_name TEXT NOT NULL CHECK(length(trim(display_name)) BETWEEN 1 AND 100),
    telegram_user_id INTEGER CHECK(telegram_user_id > 0),
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    version INTEGER NOT NULL CHECK(version > 0),
    PRIMARY KEY(group_id,participant_id),
    UNIQUE(group_id,telegram_user_id),
    FOREIGN KEY(group_id) REFERENCES app_groups(group_id)
) STRICT;

CREATE TABLE training_drafts (
    group_id TEXT NOT NULL,
    draft_id TEXT NOT NULL,
    created_by INTEGER NOT NULL CHECK(created_by > 0),
    version INTEGER NOT NULL CHECK(version > 0),
    status TEXT NOT NULL CHECK(status IN ('DRAFT','POSTED','EDITING','CANCELLED')),
    financial_version INTEGER NOT NULL CHECK(financial_version >= 0),
    occurred_on TEXT NOT NULL,
    content_json TEXT NOT NULL,
    published_json TEXT,
    PRIMARY KEY(group_id,draft_id),
    FOREIGN KEY(group_id) REFERENCES app_groups(group_id)
) STRICT;

CREATE TABLE workflow_audit (
    audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id TEXT NOT NULL,
    command_id TEXT NOT NULL,
    actor_user_id INTEGER NOT NULL CHECK(actor_user_id > 0),
    kind TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    before_json TEXT,
    after_json TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    UNIQUE(group_id,command_id),
    FOREIGN KEY(group_id) REFERENCES app_groups(group_id)
) STRICT;

CREATE TABLE workflow_commands (
    group_id TEXT NOT NULL,
    command_id TEXT NOT NULL,
    actor_user_id INTEGER NOT NULL CHECK(actor_user_id > 0),
    payload_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    audit_id INTEGER NOT NULL UNIQUE,
    PRIMARY KEY(group_id,command_id),
    FOREIGN KEY(group_id) REFERENCES app_groups(group_id),
    FOREIGN KEY(audit_id) REFERENCES workflow_audit(audit_id)
) STRICT;
