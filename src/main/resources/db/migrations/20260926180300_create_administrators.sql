CREATE TABLE IF NOT EXISTS _umbrellaz_administrators (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    created_by TEXT NOT NULL
);
