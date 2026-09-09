ALTER TABLE tg_actions ADD COLUMN payload_hash TEXT;
ALTER TABLE tg_actions ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0;
CREATE INDEX tg_action_reuse ON tg_actions(user_id,group_id,payload_hash) WHERE payload_hash IS NOT NULL;
CREATE INDEX tg_action_expiry ON tg_actions(expires_at);
CREATE INDEX tg_plan_token ON tg_plans(user_id,group_id,token,update_id);
