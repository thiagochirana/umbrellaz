package dev.chirana.umbrellaz.auth;

import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.whitelist.WhitelistService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private final PlayerService playerService;
    private final WhitelistService whitelistService;
    private final ConcurrentMap<UUID, AuthSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    public AuthService(PlayerService playerService, WhitelistService whitelistService) {
        this.playerService = playerService;
        this.whitelistService = whitelistService;
    }

    public CompletableFuture<Boolean> onJoin(UUID uuid, String username) {
        long generation = generations.incrementAndGet();
        sessions.put(uuid, AuthSession.blocked(uuid, generation));
        return playerService.recordJoin(uuid, username)
                .thenCompose(ignored -> whitelistService.whenReady())
                .thenApply(ignored -> transition(uuid, generation, whitelistService.isWhitelisted(uuid)))
                .exceptionally(throwable -> {
                    blockIfCurrent(uuid, generation);
                    LOGGER.error("Authorization lookup failed for {}", uuid, throwable);
                    return false;
                });
    }

    public void onDisconnect(UUID uuid) {
        sessions.remove(uuid);
    }

    public boolean isAuthenticated(UUID uuid) {
        AuthSession session = sessions.get(uuid);
        return session != null && session.state() == AuthState.AUTHENTICATED;
    }

    public boolean isBlocked(UUID uuid) {
        return !isAuthenticated(uuid);
    }

    public Optional<AuthSession> session(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    public void authenticate(UUID uuid) {
        reconcileAuthorization(uuid);
    }

    public void block(UUID uuid) {
        if (sessions.containsKey(uuid)) {
            sessions.computeIfPresent(uuid, (ignored, current) -> AuthSession.blocked(uuid, current.generation()));
        }
    }

    public CompletableFuture<Boolean> reconcileAuthorization(UUID uuid) {
        return whitelistService.whenReady()
                .thenApply(ignored -> transitionCurrent(uuid, whitelistService.isWhitelisted(uuid)))
                .exceptionally(throwable -> {
                    block(uuid);
                    LOGGER.error("Authorization reconciliation failed for {}", uuid, throwable);
                    return false;
                });
    }

    private boolean transition(UUID uuid, long generation, boolean allowed) {
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(false);
        sessions.computeIfPresent(uuid, (ignored, session) -> {
            if (session.generation() != generation) {
                return session;
            }
            current.set(allowed);
            return allowed ? AuthSession.authenticated(uuid, generation) : AuthSession.blocked(uuid, generation);
        });
        return current.get();
    }

    private boolean transitionCurrent(UUID uuid, boolean allowed) {
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(false);
        sessions.computeIfPresent(uuid, (ignored, session) -> {
            current.set(allowed);
            return allowed ? AuthSession.authenticated(uuid, session.generation()) : AuthSession.blocked(uuid, session.generation());
        });
        return current.get();
    }

    private void blockIfCurrent(UUID uuid, long generation) {
        sessions.computeIfPresent(uuid, (ignored, session) -> session.generation() == generation
                ? AuthSession.blocked(uuid, generation)
                : session);
    }
}
