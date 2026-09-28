package dev.chirana.umbrellaz.lock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class AttemptLimiter {
    public static final int MAX_CONSECUTIVE_FAILURES = 3;
    public static final long COOLDOWN_NANOS = 30_000_000_000L;
    public static final int MAX_TRACKED_STATES = 10_000;
    public static final int CLEANUP_BUDGET = 32;

    private final LongSupplier monotonicNanos;
    private final int capacity;
    private final Map<AttemptKey, AttemptState> states = new HashMap<>();
    private final Deque<AttemptKey> cleanupQueue = new ArrayDeque<>();
    private final Set<AttemptKey> queuedKeys = new HashSet<>();

    public AttemptLimiter() {
        this(System::nanoTime);
    }

    public AttemptLimiter(LongSupplier monotonicNanos) {
        this(monotonicNanos, MAX_TRACKED_STATES);
    }

    public AttemptLimiter(LongSupplier monotonicNanos, int capacity) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized AttemptResult recordFailure(UUID playerUuid, UUID lockerId) {
        AttemptKey key = new AttemptKey(playerUuid, lockerId);
        long now = monotonicNanos.getAsLong();
        purgeStale(now);
        AttemptState current = states.get(key);
        if (current != null && hasCooldown(current) && !cooldownExpired(now, current)) {
            return result(current, now, ResultState.COOLDOWN);
        }
        if (current != null && hasCooldown(current)) {
            states.remove(key);
            queuedKeys.remove(key);
            current = null;
        }
        int failures = current == null ? 1 : current.consecutiveFailures() + 1;
        if (current == null && states.size() >= capacity) {
            return new AttemptResult(ResultState.CAPACITY, 0, 0);
        }
        boolean cooldown = failures >= MAX_CONSECUTIVE_FAILURES;
        AttemptState updated = new AttemptState(failures, cooldown, now, now);
        states.put(key, updated);
        schedule(key);
        return result(updated, now, hasCooldown(updated) ? ResultState.COOLDOWN_STARTED : ResultState.FAILURE);
    }

    public synchronized AttemptResult recordSuccess(UUID playerUuid, UUID lockerId) {
        long now = monotonicNanos.getAsLong();
        purgeStale(now);
        AttemptKey key = new AttemptKey(playerUuid, lockerId);
        AttemptState current = states.get(key);
        if (current != null && hasCooldown(current) && !cooldownExpired(now, current)) {
            return result(current, now, ResultState.COOLDOWN);
        }
        if (current == null && states.size() >= capacity) {
            return new AttemptResult(ResultState.CAPACITY, 0, 0);
        }
        states.remove(key);
        queuedKeys.remove(key);
        return new AttemptResult(ResultState.AVAILABLE, 0, 0);
    }

    public synchronized AttemptResult status(UUID playerUuid, UUID lockerId) {
        long now = monotonicNanos.getAsLong();
        purgeStale(now);
        AttemptKey key = new AttemptKey(playerUuid, lockerId);
        AttemptState state = states.get(key);
        if (state == null) {
            return new AttemptResult(ResultState.AVAILABLE, 0, 0);
        }
        if (hasCooldown(state) && cooldownExpired(now, state)) {
            states.remove(key);
            queuedKeys.remove(key);
            return new AttemptResult(ResultState.AVAILABLE, 0, 0);
        }
        return result(state, now, hasCooldown(state) ? ResultState.COOLDOWN : ResultState.FAILURE);
    }

    public boolean isCoolingDown(UUID playerUuid, UUID lockerId) {
        return status(playerUuid, lockerId).state() == ResultState.COOLDOWN;
    }

    public synchronized void resetOnRestart() {
        states.clear();
        cleanupQueue.clear();
        queuedKeys.clear();
    }

    public synchronized int trackedStateCount() {
        purgeStale(monotonicNanos.getAsLong());
        return states.size();
    }

    private AttemptResult result(AttemptState state, long now, ResultState resultState) {
        long elapsed = hasCooldown(state) ? Math.max(0, now - state.cooldownStartedNanos()) : 0;
        long remaining = hasCooldown(state) ? Math.max(0, COOLDOWN_NANOS - elapsed) : 0;
        return new AttemptResult(resultState, state.consecutiveFailures(), remaining);
    }

    private void purgeStale(long now) {
        for (int index = 0; index < CLEANUP_BUDGET && !cleanupQueue.isEmpty(); index++) {
            AttemptKey key = cleanupQueue.removeFirst();
            queuedKeys.remove(key);
            AttemptState state = states.get(key);
            if (state == null) {
                continue;
            }
            boolean stale = hasCooldown(state)
                    ? cooldownExpired(now, state)
                    : now - state.lastFailureNanos() >= COOLDOWN_NANOS;
            if (stale) {
                states.remove(key);
            } else {
                schedule(key);
            }
        }
    }

    private void schedule(AttemptKey key) {
        if (queuedKeys.add(key)) {
            cleanupQueue.addLast(key);
        }
    }

    private boolean hasCooldown(AttemptState state) {
        return state.cooldown();
    }

    private boolean cooldownExpired(long now, AttemptState state) {
        return now - state.cooldownStartedNanos() >= COOLDOWN_NANOS;
    }

    private record AttemptKey(UUID playerUuid, UUID lockerId) {
        private AttemptKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(lockerId, "lockerId");
        }
    }

    private record AttemptState(int consecutiveFailures, boolean cooldown, long cooldownStartedNanos,
                                long lastFailureNanos) {
    }

    public enum ResultState {
        AVAILABLE,
        FAILURE,
        COOLDOWN_STARTED,
        COOLDOWN,
        CAPACITY
    }

    public record AttemptResult(ResultState state, int consecutiveFailures, long cooldownRemainingNanos) {
        public AttemptResult {
            Objects.requireNonNull(state, "state");
        }

        public boolean accepted() {
            return state == ResultState.AVAILABLE;
        }
    }
}
