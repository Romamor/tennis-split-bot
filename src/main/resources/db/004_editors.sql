CREATE TABLE tg_editors (
    editor_id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    group_id TEXT NOT NULL,
    draft_id TEXT NOT NULL,
    editor_json TEXT NOT NULL,
    UNIQUE(user_id,group_id,draft_id),
    FOREIGN KEY(group_id) REFERENCES tg_groups(group_id)
) STRICT;
CREATE INDEX roster_revision ON workflow_audit(group_id,audit_id)
WHERE kind IN ('add_participant','rename_participant','post_draft','cancel_draft','commit_draft');
