DROP INDEX IF EXISTS idx_audit_events_occurred_at_ms;
DROP INDEX IF EXISTS idx_audit_events_action_sequence;
DROP INDEX IF EXISTS idx_audit_events_actor_uuid;
DROP INDEX IF EXISTS idx_audit_events_target;

ALTER TABLE audit_events RENAME TO audit_events_legacy;

CREATE TABLE audit_events (
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
    actor_type TEXT NOT NULL CHECK (actor_type IN ('player', 'console', 'system', 'unknown')),
    actor_display_name TEXT,
    target_type TEXT,
    target_id TEXT,
    reason_code TEXT,
    payload_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL CHECK (length(CAST(payload_json AS BLOB)) <= 8192),
    CHECK (length(event_id) = 36
        AND substr(event_id, 9, 1) = '-'
        AND substr(event_id, 14, 1) = '-'
        AND substr(event_id, 19, 1) = '-'
        AND substr(event_id, 24, 1) = '-'
        AND length(replace(event_id, '-', '')) = 32
        AND replace(event_id, '-', '') NOT GLOB '*[^0-9a-fA-F]*'),
    CHECK (length(runtime_id) = 36
        AND substr(runtime_id, 9, 1) = '-'
        AND substr(runtime_id, 14, 1) = '-'
        AND substr(runtime_id, 19, 1) = '-'
        AND substr(runtime_id, 24, 1) = '-'
        AND length(replace(runtime_id, '-', '')) = 32
        AND replace(runtime_id, '-', '') NOT GLOB '*[^0-9a-fA-F]*'),
    CHECK (length(correlation_id) = 36
        AND substr(correlation_id, 9, 1) = '-'
        AND substr(correlation_id, 14, 1) = '-'
        AND substr(correlation_id, 19, 1) = '-'
        AND substr(correlation_id, 24, 1) = '-'
        AND length(replace(correlation_id, '-', '')) = 32
        AND replace(correlation_id, '-', '') NOT GLOB '*[^0-9a-fA-F]*'),
    CHECK ((actor_type = 'player'
            AND actor_uuid IS NOT NULL
            AND length(actor_uuid) = 36
            AND substr(actor_uuid, 9, 1) = '-'
            AND substr(actor_uuid, 14, 1) = '-'
            AND substr(actor_uuid, 19, 1) = '-'
            AND substr(actor_uuid, 24, 1) = '-'
            AND length(replace(actor_uuid, '-', '')) = 32
            AND replace(actor_uuid, '-', '') NOT GLOB '*[^0-9a-fA-F]*')
        OR (actor_type <> 'player' AND actor_uuid IS NULL)),
    CHECK ((target_type IS NULL AND target_id IS NULL)
        OR (target_type IS NOT NULL AND target_id IS NOT NULL
            AND length(target_type) > 0
            AND length(target_type) <= 64
            AND substr(target_type, 1, 1) GLOB '[a-z0-9]'
            AND target_type NOT GLOB '*[^a-z0-9._:-]*'
            AND length(target_id) > 0
            AND length(target_id) <= 256
            AND substr(target_id, 1, 1) GLOB '[a-z0-9]'
            AND target_id NOT GLOB '*[^a-z0-9._:/,-]*'))
);

INSERT INTO audit_events(
    sequence, event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, source, action, outcome,
    actor_uuid, actor_type, actor_display_name, target_type, target_id, reason_code, payload_version, payload_json
)
SELECT sequence, event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, source, action, outcome,
       actor_uuid, actor_type, actor_display_name, target_type, target_id, reason_code, payload_version, payload_json
FROM audit_events_legacy;

DROP TABLE audit_events_legacy;

CREATE INDEX idx_audit_events_occurred_at_ms
    ON audit_events(occurred_at_ms, sequence);
CREATE INDEX idx_audit_events_action_sequence
    ON audit_events(action, sequence DESC);
CREATE INDEX idx_audit_events_actor_uuid
    ON audit_events(actor_uuid, sequence DESC)
    WHERE actor_uuid IS NOT NULL;
CREATE INDEX idx_audit_events_target
    ON audit_events(target_type, target_id, sequence);
