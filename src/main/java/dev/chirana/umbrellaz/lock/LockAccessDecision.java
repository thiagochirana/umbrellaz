package dev.chirana.umbrellaz.lock;

public enum LockAccessDecision {
    NOT_READY,
    NOT_LOCKED,
    LOCKED,
    GRANTED,
    WRONG_PASSWORD,
    COOLDOWN,
    VERIFICATION_BUSY
}
