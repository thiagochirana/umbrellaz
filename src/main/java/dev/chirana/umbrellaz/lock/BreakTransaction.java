package dev.chirana.umbrellaz.lock;

final class BreakTransaction {
    private Phase phase = Phase.RESERVED;

    synchronized boolean markPhysicalDestruction(boolean destroyed) {
        if (phase != Phase.RESERVED) {
            return false;
        }
        phase = destroyed ? Phase.DESTROYED : Phase.CANCELLED;
        return destroyed;
    }

    synchronized boolean markInvalidation(boolean invalidated) {
        if (phase != Phase.DESTROYED || !invalidated) {
            return false;
        }
        phase = Phase.COMMITTED;
        return true;
    }

    synchronized boolean isPending() {
        return phase == Phase.RESERVED || phase == Phase.DESTROYED;
    }

    private enum Phase {
        RESERVED,
        DESTROYED,
        CANCELLED,
        COMMITTED
    }
}
