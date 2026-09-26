package dev.chirana.umbrellaz.player;

import java.time.Instant;
import java.util.UUID;

public record Player(UUID uuid, String username, Instant createdAt, Instant updatedAt) {
}
