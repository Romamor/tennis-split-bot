ALTER TABLE tg_actions ADD COLUMN active INTEGER NOT NULL DEFAULT 0 CHECK(active IN (0,1));
CREATE INDEX tg_active_actions ON tg_actions(user_id) WHERE active=1;
DROP INDEX tg_action_expiry;
CREATE INDEX tg_action_expiry ON tg_actions(expires_at) WHERE active=0;
