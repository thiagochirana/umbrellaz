package dev.chirana.umbrellaz.player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FoodLockService {
    private final ConcurrentMap<UUID, Integer> lockedFood = new ConcurrentHashMap<>();

    public void lock(UUID playerUuid, int foodLevel) {
        lockedFood.put(playerUuid, foodLevel);
    }

    public void unlock(UUID playerUuid) {
        lockedFood.remove(playerUuid);
    }

    public void clear(UUID playerUuid) {
        unlock(playerUuid);
    }

    public Optional<Integer> lockedFood(UUID playerUuid) {
        return Optional.ofNullable(lockedFood.get(playerUuid));
    }
}
