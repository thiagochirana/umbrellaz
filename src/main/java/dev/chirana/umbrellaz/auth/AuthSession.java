package dev.chirana.umbrellaz.auth;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(UUID playerUuid, long generation, AuthState state, Instant authenticatedAt) {
    public static AuthSession blocked(UUID playerUuid, long generation) {
        return new AuthSession(playerUuid, generation, AuthState.BLOCKED, null);
    }

    public static AuthSession authenticated(UUID playerUuid, long generation) {
        return new AuthSession(playerUuid, generation, AuthState.AUTHENTICATED, Instant.now());
    }
}
