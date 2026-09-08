CREATE TABLE group_state (
    group_id TEXT PRIMARY KEY,
    revision INTEGER NOT NULL CHECK(revision > 0),
    checkpoint_json TEXT NOT NULL
) STRICT;

CREATE TABLE operations (
    group_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK(sequence > 0),
    command_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    represented_party TEXT,
    entity_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    entity_version INTEGER NOT NULL CHECK(entity_version > 0),
    command_json TEXT NOT NULL,
    details_json TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    PRIMARY KEY(group_id, sequence),
    UNIQUE(group_id, command_id),
    FOREIGN KEY(group_id) REFERENCES group_state(group_id)
) STRICT;

CREATE TABLE balance_entries (
    group_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    entry_index INTEGER NOT NULL CHECK(entry_index >= 0),
    participant_id TEXT NOT NULL,
    amount INTEGER NOT NULL CHECK(amount >= -9223372036854775807),
    PRIMARY KEY(group_id, sequence, entry_index),
    FOREIGN KEY(group_id, sequence) REFERENCES operations(group_id, sequence)
) STRICT;

CREATE TABLE outbox (
    group_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    acknowledged_at TEXT,
    PRIMARY KEY(group_id, sequence),
    FOREIGN KEY(group_id, sequence) REFERENCES operations(group_id, sequence)
) STRICT;

CREATE INDEX pending_outbox ON outbox(group_id, sequence) WHERE acknowledged_at IS NULL;
