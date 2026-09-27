package dev.chirana.umbrellaz.player;

import java.time.Instant;
import java.util.UUID;

public record Player(UUID uuid, String username, Instant createdAt, Instant updatedAt, String alias) {
    public Player(UUID uuid, String username, Instant createdAt, Instant updatedAt) {
        this(uuid, username, createdAt, updatedAt, null);
    }

    public Player(UUID uuid, String username, String alias, Instant createdAt, Instant updatedAt) {
        this(uuid, username, createdAt, updatedAt, alias);
    }

    public String playerAlias() {
        return alias;
    }
}
