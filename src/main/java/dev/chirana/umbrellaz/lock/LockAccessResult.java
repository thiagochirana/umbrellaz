package dev.chirana.umbrellaz.lock;

import java.util.Objects;
import java.util.UUID;

public record LockAccessResult(LockAccessDecision decision, LockAction action, UUID lockerId,
                               UUID ownerUuid, long cooldownRemainingNanos) {
    public LockAccessResult {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(action, "action");
        if (cooldownRemainingNanos < 0) {
            throw new IllegalArgumentException("cooldownRemainingNanos must not be negative");
        }
        if (decision == LockAccessDecision.NOT_LOCKED || decision == LockAccessDecision.NOT_READY) {
            if (lockerId != null || ownerUuid != null) {
                throw new IllegalArgumentException("unresolved access must not expose locker identity");
            }
        }
    }

    public boolean mayPerformAction() {
        return decision == LockAccessDecision.GRANTED || decision == LockAccessDecision.NOT_LOCKED;
    }
}
