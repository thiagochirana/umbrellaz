CREATE TABLE IF NOT EXISTS audit_events (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    runtime_id TEXT NOT NULL,
    correlation_id TEXT NOT NULL,
    occurred_at_ms INTEGER NOT NULL,
    recorded_at_ms INTEGER NOT NULL,
    source TEXT NOT NULL,
    action TEXT NOT NULL,
    outcome TEXT NOT NULL,
    actor_uuid TEXT,
    actor_type TEXT NOT NULL,
    actor_display_name TEXT,
    target_type TEXT,
    target_id TEXT,
    reason_code TEXT,
    payload_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL CHECK (length(CAST(payload_json AS BLOB)) <= 8192),
    CHECK ((target_type IS NULL AND target_id IS NULL) OR (target_type IS NOT NULL AND target_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_audit_events_occurred_at_ms
    ON audit_events(occurred_at_ms, sequence);
CREATE INDEX IF NOT EXISTS idx_audit_events_action_sequence
    ON audit_events(action, sequence DESC);
CREATE INDEX IF NOT EXISTS idx_audit_events_actor_uuid
    ON audit_events(actor_uuid);
CREATE INDEX IF NOT EXISTS idx_audit_events_target
    ON audit_events(target_type, target_id, sequence);
