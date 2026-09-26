package dev.chirana.umbrellaz.whitelist;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WhitelistCache {
    private final Set<UUID> whitelistedPlayers = ConcurrentHashMap.newKeySet();

    public boolean contains(UUID uuid) {
        return whitelistedPlayers.contains(uuid);
    }

    public void add(UUID uuid) {
        whitelistedPlayers.add(uuid);
    }

    public void remove(UUID uuid) {
        whitelistedPlayers.remove(uuid);
    }

    public void replace(Set<UUID> uuids) {
        whitelistedPlayers.clear();
        whitelistedPlayers.addAll(uuids);
    }

    public Set<UUID> snapshot() {
        return Set.copyOf(whitelistedPlayers);
    }
}
