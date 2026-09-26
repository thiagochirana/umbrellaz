package dev.chirana.umbrellaz.player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class HealthLockService {
    private final ConcurrentMap<UUID, Float> lockedHealth = new ConcurrentHashMap<>();

    public void lock(UUID playerUuid, float health) {
        lockedHealth.put(playerUuid, health);
    }

    public void unlock(UUID playerUuid) {
        lockedHealth.remove(playerUuid);
    }

    public void clear(UUID playerUuid) {
        unlock(playerUuid);
    }

    public Optional<Float> lockedHealth(UUID playerUuid) {
        return Optional.ofNullable(lockedHealth.get(playerUuid));
    }
}
