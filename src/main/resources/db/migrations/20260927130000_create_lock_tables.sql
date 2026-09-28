CREATE TABLE IF NOT EXISTS lock_placements (
    world_id TEXT NOT NULL,
    dimension_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    generation_id TEXT NOT NULL,
    placed_by TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    block_type TEXT NOT NULL,
    placed_at TEXT NOT NULL,
    PRIMARY KEY (world_id, dimension_id, x, y, z),
    UNIQUE (world_id, dimension_id, x, y, z, generation_id)
);

CREATE TABLE IF NOT EXISTS lockers (
    locker_id TEXT PRIMARY KEY,
    owner_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    password_salt BLOB NOT NULL,
    password_hash BLOB NOT NULL,
    password_algorithm TEXT NOT NULL,
    password_work_factor INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    generation INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS locker_members (
    locker_id TEXT NOT NULL REFERENCES lockers(locker_id) ON DELETE CASCADE,
    world_id TEXT NOT NULL,
    dimension_id TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    generation_id TEXT NOT NULL,
    expected_block_type TEXT NOT NULL,
    installer_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    PRIMARY KEY (world_id, dimension_id, x, y, z),
    FOREIGN KEY (world_id, dimension_id, x, y, z, generation_id)
        REFERENCES lock_placements(world_id, dimension_id, x, y, z, generation_id)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_lock_placements_placed_by ON lock_placements(placed_by);
CREATE INDEX IF NOT EXISTS idx_lock_placements_target
    ON lock_placements(world_id, dimension_id, x, y, z, placed_at);
CREATE INDEX IF NOT EXISTS idx_locker_members_locker_id ON locker_members(locker_id);
