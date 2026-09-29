package dev.chirana.umbrellaz.protocol;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class ActionContextRegistry {
    public static final int MAX_CONTEXTS = 4096;
    public static final int MAX_ACTION_LENGTH = 64;
    public static final int MAX_TARGET_LENGTH = 256;
    private static final Duration MAX_LIFETIME = Duration.ofHours(1);
    private final Map<UUID, Context> contexts = new ConcurrentHashMap<>();

    public Context open(UUID connectionId, UUID playerUuid, UUID nonce, long generation,
                        String action, String target, Duration lifetime) {
        long lifetimeNanos = validate(connectionId, playerUuid, nonce, generation, action, target, lifetime);
        synchronized (contexts) {
            removeTerminalContexts();
            if (contexts.size() >= MAX_CONTEXTS) {
                throw new IllegalStateException("Action context capacity reached");
            }

            UUID token;
            do {
                token = UUID.randomUUID();
            } while (contexts.containsKey(token));
            Context context = new Context(token, connectionId, playerUuid, nonce, generation,
                    action, target, System.nanoTime() + lifetimeNanos);
            contexts.put(token, context);
            return context;
        }
    }

    public Optional<Context> claim(UUID token, UUID connectionId, UUID playerUuid,
                                   UUID nonce, long generation) {
        if (token == null || connectionId == null || playerUuid == null || nonce == null || generation < 0) {
            return Optional.empty();
        }
        Context context = contexts.get(token);
        if (context == null || !context.matches(connectionId, playerUuid, nonce, generation)) {
            return Optional.empty();
        }
        return context.claim() ? Optional.of(context) : Optional.empty();
    }

    public boolean complete(Context context) {
        return owns(context) && context.complete();
    }

    public boolean complete(Context context, UUID connectionId, UUID playerUuid,
                            UUID nonce, long generation) {
        if (!owns(context) || !validBinding(connectionId, playerUuid, nonce, generation)
                || !context.matches(connectionId, playerUuid, nonce, generation)) {
            return false;
        }
        return context.complete();
    }

    public boolean cancel(UUID token, UUID connectionId, UUID playerUuid,
                          UUID nonce, long generation) {
        if (token == null || !validBinding(connectionId, playerUuid, nonce, generation)) {
            return false;
        }
        Context context = contexts.get(token);
        return context != null && context.matches(connectionId, playerUuid, nonce, generation)
                && context.cancel();
    }

    public void invalidateConnection(UUID connectionId) {
        if (connectionId == null) return;
        contexts.values().stream()
                .filter(context -> context.connectionId().equals(connectionId))
                .forEach(context -> context.terminal(ContextState.INVALIDATED));
    }

    public void invalidateAll() {
        synchronized (contexts) {
            contexts.values().forEach(context -> context.terminal(ContextState.INVALIDATED));
            contexts.clear();
        }
    }

    private static long validate(UUID connectionId, UUID playerUuid, UUID nonce, long generation,
                                 String action, String target, Duration lifetime) {
        if (connectionId == null || playerUuid == null || connectionId.equals(playerUuid) || nonce == null
                || generation < 0
                || action == null || action.isEmpty() || action.length() > MAX_ACTION_LENGTH
                || target == null || target.isEmpty() || target.length() > MAX_TARGET_LENGTH
                || lifetime == null || lifetime.isNegative() || lifetime.isZero()
                || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("Invalid action context");
        }
        try {
            return lifetime.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid action context", failure);
        }
    }

    private static boolean validBinding(UUID connectionId, UUID playerUuid, UUID nonce, long generation) {
        return connectionId != null && playerUuid != null && nonce != null && generation >= 0;
    }

    private boolean owns(Context context) {
        return context != null && contexts.get(context.token()) == context;
    }

    private void removeTerminalContexts() {
        contexts.values().removeIf(context -> switch (context.state()) {
            case COMPLETED, CANCELLED, INVALIDATED, EXPIRED -> true;
            default -> false;
        });
    }

    public enum ContextState { OPEN, IN_FLIGHT, COMPLETED, CANCELLED, INVALIDATED, EXPIRED }

    public final class Context {
        private final UUID token;
        private final UUID connectionId;
        private final UUID playerUuid;
        private final UUID nonce;
        private final long generation;
        private final String action;
        private final String target;
        private final long expiresAtNanos;
        private final AtomicReference<ContextState> state = new AtomicReference<>(ContextState.OPEN);

        private Context(UUID token, UUID connectionId, UUID playerUuid, UUID nonce, long generation,
                        String action, String target, long expiresAtNanos) {
            this.token = token;
            this.connectionId = connectionId;
            this.playerUuid = playerUuid;
            this.nonce = nonce;
            this.generation = generation;
            this.action = action;
            this.target = target;
            this.expiresAtNanos = expiresAtNanos;
        }

        public UUID token() { return token; }
        public UUID connectionId() { return connectionId; }
        public UUID playerUuid() { return playerUuid; }
        public UUID nonce() { return nonce; }
        public long generation() { return generation; }
        public String action() { return action; }
        public String target() { return target; }

        public ContextState state() {
            expireIfNeeded();
            return state.get();
        }

        private boolean matches(UUID connectionId, UUID playerUuid, UUID nonce, long generation) {
            return this.connectionId.equals(connectionId) && this.playerUuid.equals(playerUuid)
                    && this.nonce.equals(nonce) && this.generation == generation;
        }

        private boolean claim() {
            expireIfNeeded();
            return state.compareAndSet(ContextState.OPEN, ContextState.IN_FLIGHT);
        }

        private boolean complete() {
            expireIfNeeded();
            while (true) {
                ContextState current = state.get();
                if (current == ContextState.COMPLETED) {
                    return true;
                }
                if (current != ContextState.IN_FLIGHT) {
                    return false;
                }
                if (state.compareAndSet(ContextState.IN_FLIGHT, ContextState.COMPLETED)) {
                    return true;
                }
            }
        }

        private boolean cancel() {
            expireIfNeeded();
            while (true) {
                ContextState current = state.get();
                if (current == ContextState.CANCELLED) {
                    return true;
                }
                if (current != ContextState.OPEN && current != ContextState.IN_FLIGHT) {
                    return false;
                }
                if (state.compareAndSet(current, ContextState.CANCELLED)) {
                    return true;
                }
            }
        }

        private boolean terminal(ContextState terminal) {
            expireIfNeeded();
            while (true) {
                ContextState current = state.get();
                if (current == terminal || current == ContextState.COMPLETED
                        || current == ContextState.CANCELLED || current == ContextState.INVALIDATED
                        || current == ContextState.EXPIRED) {
                    return current == terminal;
                }
                if (current == ContextState.OPEN && terminal != ContextState.INVALIDATED) {
                    return false;
                }
                if (state.compareAndSet(current, terminal)) {
                    return true;
                }
            }
        }

        private void expireIfNeeded() {
            if (System.nanoTime() >= expiresAtNanos) {
                state.compareAndSet(ContextState.OPEN, ContextState.EXPIRED);
                state.compareAndSet(ContextState.IN_FLIGHT, ContextState.EXPIRED);
            }
        }
    }
}
