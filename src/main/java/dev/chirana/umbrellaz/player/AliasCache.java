package dev.chirana.umbrellaz.player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AliasCache {
    private final Map<String, UUID> aliases = new HashMap<>();
    private final Map<UUID, String> aliasesByPlayer = new HashMap<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private boolean available;
    private boolean failed;

    public synchronized void replace(Map<UUID, String> values) {
        if (failed) {
            throw new IllegalStateException("Alias cache is unavailable");
        }
        Map<String, UUID> nextAliases = new HashMap<>();
        Map<UUID, String> nextByPlayer = new HashMap<>();
        values.forEach((uuid, alias) -> {
            String canonical = canonical(alias);
            UUID existing = nextAliases.putIfAbsent(canonical, uuid);
            if (existing != null && !existing.equals(uuid)) {
                throw new IllegalArgumentException("Duplicate alias: " + canonical);
            }
            nextByPlayer.put(uuid, canonical);
        });
        aliases.clear();
        aliasesByPlayer.clear();
        aliases.putAll(nextAliases);
        aliasesByPlayer.putAll(nextByPlayer);
        available = true;
        ready.complete(null);
    }

    public synchronized void put(UUID playerUuid, String alias) {
        String canonical = canonical(alias);
        UUID existingOwner = aliases.get(canonical);
        if (existingOwner != null && !existingOwner.equals(playerUuid)) {
            throw new IllegalArgumentException("Duplicate alias: " + canonical);
        }
        String previousAlias = aliasesByPlayer.put(playerUuid, canonical);
        if (previousAlias != null) {
            aliases.remove(normalize(previousAlias));
        }
        aliases.put(canonical, playerUuid);
    }

    public synchronized Optional<UUID> find(String alias) {
        return available ? Optional.ofNullable(aliases.get(normalize(alias))) : Optional.empty();
    }

    public synchronized Optional<String> aliasFor(UUID playerUuid) {
        return available ? Optional.ofNullable(aliasesByPlayer.get(playerUuid)) : Optional.empty();
    }

    public synchronized boolean isReady() {
        return available;
    }

    public CompletableFuture<Void> whenReady() {
        return ready;
    }

    public synchronized void fail() {
        available = false;
        failed = true;
        ready.completeExceptionally(new IllegalStateException("Alias cache is unavailable"));
    }

    private String canonical(String alias) {
        return AliasRules.canonicalize(alias)
                .orElseThrow(() -> new IllegalArgumentException("Invalid alias"));
    }

    private String normalize(String alias) {
        return alias.toLowerCase(Locale.ROOT);
    }
}
