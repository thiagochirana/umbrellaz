package dev.chirana.umbrellaz.authorization;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdministratorCache {
    private final Set<UUID> administrators = ConcurrentHashMap.newKeySet();

    public boolean contains(UUID playerUuid) {
        return administrators.contains(playerUuid);
    }

    public void replace(Set<UUID> playerUuids) {
        administrators.clear();
        administrators.addAll(playerUuids);
    }
}
