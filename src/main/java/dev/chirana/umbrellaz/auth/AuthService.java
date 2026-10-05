package dev.chirana.umbrellaz.auth;

import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.audit.Target;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.whitelist.WhitelistService;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final String UNKNOWN_USERNAME = "unknown";
    private static final Actor AUTH_ACTOR = new Actor(ActorType.SYSTEM, null, "server");
    private final PlayerService playerService;
    private final WhitelistService whitelistService;
    private final AuditService auditService;
    private final ConcurrentMap<UUID, AuthSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> usernames = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    public AuthService(PlayerService playerService, WhitelistService whitelistService) {
        this.playerService = playerService;
        this.whitelistService = whitelistService;
        this.auditService = null;
    }

    public AuthService(PlayerService playerService, WhitelistService whitelistService, AuditService auditService) {
        this.playerService = playerService;
        this.whitelistService = whitelistService;
        this.auditService = Objects.requireNonNull(auditService, "Audit service must not be null");
    }

    public CompletableFuture<Boolean> onJoin(UUID uuid, String username) {
        Objects.requireNonNull(uuid, "Player UUID must not be null");
        long generation = generations.incrementAndGet();
        String usernameSnapshot = usernameSnapshot(username);
        sessions.put(uuid, AuthSession.blocked(uuid, generation));
        usernames.put(uuid, usernameSnapshot);
        return playerService.recordJoin(uuid, username)
                .thenCompose(ignored -> whitelistService.whenReady())
                .thenApply(ignored -> authorizeJoin(uuid, generation, usernameSnapshot,
                        whitelistService.isWhitelisted(uuid)))
                .exceptionally(throwable -> {
                    boolean current = blockIfCurrent(uuid, generation, usernameSnapshot, "lookup_failed");
                    LOGGER.error("Authorization lookup failed for {}", uuid, throwable);
                    emitJoin(uuid, usernameSnapshot, current ? "lookup_failed" : "stale",
                            current ? Outcome.FAILURE : Outcome.CANCELLED);
                    return false;
                });
    }

    public void onDisconnect(UUID uuid) {
        Objects.requireNonNull(uuid, "Player UUID must not be null");
        String usernameSnapshot = usernames.remove(uuid);
        sessions.remove(uuid);
        if (auditService != null) {
            try {
                emit(AuditActions.AUTH_LEFT, Outcome.SUCCESS, "disconnect", uuid,
                        AuditPayload.forAction(AuditActions.AUTH_LEFT, AuditPayload.playerUuid(uuid),
                                AuditPayload.playerName(usernameSnapshot == null ? UNKNOWN_USERNAME : usernameSnapshot)));
            } catch (RuntimeException failure) {
                LOGGER.warn("Authorization audit failed for {}", uuid);
            }
        }
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
        Objects.requireNonNull(uuid, "Player UUID must not be null");
        transitionCurrent(uuid, false, false, "blocked", usernameFor(uuid));
    }

    public CompletableFuture<Boolean> reconcileAuthorization(UUID uuid) {
        Objects.requireNonNull(uuid, "Player UUID must not be null");
        String usernameSnapshot = usernameFor(uuid);
        return whitelistService.whenReady()
                .thenApply(ignored -> {
                    boolean allowed = whitelistService.isWhitelisted(uuid);
                    return transitionCurrent(uuid, allowed, true, allowed ? "success" : "blocked", usernameSnapshot);
                })
                .exceptionally(throwable -> {
                    transitionCurrent(uuid, false, true, "lookup_failed", usernameSnapshot);
                    LOGGER.error("Authorization reconciliation failed for {}", uuid, throwable);
                    return false;
                });
    }

    private boolean authorizeJoin(UUID uuid, long generation, String usernameSnapshot, boolean allowed) {
        SessionTransition transition = transition(uuid, generation, allowed, false,
                allowed ? "success" : "blocked", usernameSnapshot);
        if (transition == null) {
            emitJoin(uuid, usernameSnapshot, "stale", Outcome.CANCELLED);
            return false;
        }
        String result = allowed ? "authenticated" : "blocked";
        emitJoin(uuid, usernameSnapshot, result, allowed ? Outcome.SUCCESS : Outcome.DENIED);
        return transition.after().state() == AuthState.AUTHENTICATED;
    }

    private SessionTransition transition(UUID uuid, long generation, boolean allowed, boolean emitUnchanged,
            String result, String usernameSnapshot) {
        AtomicReference<SessionTransition> transition = new AtomicReference<>();
        sessions.computeIfPresent(uuid, (ignored, session) -> {
            if (session.generation() != generation) {
                return session;
            }
            AuthSession next = nextSession(uuid, generation, session, allowed);
            transition.set(new SessionTransition(session, next));
            return next;
        });
        SessionTransition changed = transition.get();
        if (changed != null && (changed.changed() || emitUnchanged)) {
            emitSessionChange(uuid, usernameSnapshot, changed.before(), changed.after(), result);
        }
        return changed;
    }

    private boolean transitionCurrent(UUID uuid, boolean allowed, boolean emitUnchanged, String result,
            String usernameSnapshot) {
        AtomicReference<SessionTransition> transition = new AtomicReference<>();
        sessions.computeIfPresent(uuid, (ignored, session) -> {
            AuthSession next = nextSession(uuid, session.generation(), session, allowed);
            transition.set(new SessionTransition(session, next));
            return next;
        });
        SessionTransition changed = transition.get();
        if (changed != null && (changed.changed() || emitUnchanged)) {
            emitSessionChange(uuid, usernameSnapshot, changed.before(), changed.after(), result);
        }
        return changed != null && changed.after().state() == AuthState.AUTHENTICATED;
    }

    private boolean blockIfCurrent(UUID uuid, long generation, String usernameSnapshot, String result) {
        AtomicReference<SessionTransition> transition = new AtomicReference<>();
        sessions.computeIfPresent(uuid, (ignored, session) -> {
            if (session.generation() != generation) {
                return session;
            }
            AuthSession next = nextSession(uuid, generation, session, false);
            transition.set(new SessionTransition(session, next));
            return next;
        });
        SessionTransition changed = transition.get();
        if (changed != null) {
            emitSessionChange(uuid, usernameSnapshot, changed.before(), changed.after(), result);
        }
        return changed != null;
    }

    private AuthSession nextSession(UUID uuid, long generation, AuthSession current, boolean allowed) {
        if (allowed == (current.state() == AuthState.AUTHENTICATED)) {
            return current;
        }
        return allowed ? AuthSession.authenticated(uuid, generation) : AuthSession.blocked(uuid, generation);
    }

    private String usernameFor(UUID uuid) {
        return usernames.getOrDefault(uuid, UNKNOWN_USERNAME);
    }

    private void emitJoin(UUID uuid, String usernameSnapshot, String result, Outcome outcome) {
        if (auditService == null) {
            return;
        }
        try {
            emit(AuditActions.AUTH_JOINED, outcome, "join_authorization", uuid,
                    AuditPayload.forAction(AuditActions.AUTH_JOINED, AuditPayload.playerUuid(uuid),
                            AuditPayload.playerName(usernameSnapshot), AuditPayload.result(result)));
        } catch (RuntimeException failure) {
            LOGGER.warn("Authorization audit failed for {}", uuid);
        }
    }

    private void emitSessionChange(UUID uuid, String usernameSnapshot, AuthSession before, AuthSession after,
            String result) {
        if (auditService == null) {
            return;
        }
        try {
            emit(AuditActions.AUTH_SESSION_CHANGED,
                    result.equals("lookup_failed") ? Outcome.FAILURE : Outcome.SUCCESS,
                    "session_transition", uuid,
                    AuditPayload.forAction(AuditActions.AUTH_SESSION_CHANGED, AuditPayload.playerUuid(uuid),
                            AuditPayload.playerName(usernameSnapshot), AuditPayload.fromState(stateCode(before.state())),
                            AuditPayload.toState(stateCode(after.state())), AuditPayload.result(result)));
        } catch (RuntimeException failure) {
            LOGGER.warn("Authorization audit failed for {}", uuid);
        }
    }

    private void emit(String action, Outcome outcome, String reasonCode, UUID uuid, AuditPayload payload) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.record(new AuditRecordRequest(
                    AuditRecordContext.forActor(AUTH_ACTOR), Source.AUTH, action, outcome,
                    new Target("player", uuid.toString()), reasonCode, 1, payload, AuditDelivery.BEST_EFFORT))
                    .exceptionally(failure -> {
                        LOGGER.warn("Authorization audit failed for {}", uuid);
                        return null;
                    });
        } catch (RuntimeException failure) {
            LOGGER.warn("Authorization audit failed for {}", uuid);
        }
    }

    private static String stateCode(AuthState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private static String usernameSnapshot(String username) {
        if (username == null || username.isBlank()) {
            return UNKNOWN_USERNAME;
        }
        StringBuilder bounded = new StringBuilder(Math.min(username.length(), 64));
        for (int offset = 0; offset < username.length() && bounded.length() < 64;) {
            int codePoint = username.codePointAt(offset);
            int characterCount = Character.charCount(codePoint);
            offset += characterCount;
            if (Character.isISOControl(codePoint)
                    || codePoint <= Character.MAX_VALUE && Character.isSurrogate((char) codePoint)) {
                bounded.append('?');
            } else if (bounded.length() + characterCount <= 64) {
                bounded.appendCodePoint(codePoint);
            }
        }
        return bounded.isEmpty() || bounded.toString().isBlank() ? UNKNOWN_USERNAME : bounded.toString();
    }

    private record SessionTransition(AuthSession before, AuthSession after) {
        private boolean changed() {
            return before.state() != after.state();
        }
    }
}
