ALTER TABLE players ADD COLUMN player_alias TEXT COLLATE NOCASE
    CHECK (player_alias IS NULL
        OR (length(player_alias) BETWEEN 1 AND 32
            AND player_alias NOT GLOB '*[^A-Za-z0-9_]*'
            AND player_alias = lower(player_alias)
            AND lower(player_alias) <> 'self'));

CREATE UNIQUE INDEX IF NOT EXISTS idx_players_player_alias_unique
    ON players(player_alias COLLATE NOCASE)
    WHERE player_alias IS NOT NULL;
