package dev.chirana.umbrellaz.whitelist;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.PlayerService;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class WhitelistService {
    private final WhitelistRepository repository;
    private final PlayerService playerService;
    private final DatabaseExecutor databaseExecutor;
    private final WhitelistCache cache;
    private final CompletableFuture<Void> cacheReady = new CompletableFuture<>();

    public WhitelistService(WhitelistRepository repository, PlayerService playerService, DatabaseExecutor databaseExecutor, WhitelistCache cache) {
        this.repository = repository;
        this.playerService = playerService;
        this.databaseExecutor = databaseExecutor;
        this.cache = cache;
    }

    public CompletableFuture<Void> loadCache() {
        return databaseExecutor.submit(repository::findAllUuids).thenAccept(cache::replace)
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        cacheReady.complete(null);
                    } else {
                        cacheReady.completeExceptionally(throwable);
                    }
                });
    }

    public CompletableFuture<Void> whenReady() {
        return cacheReady;
    }

    public boolean isWhitelisted(UUID uuid) {
        return cache.contains(uuid);
    }

    public CompletableFuture<Optional<UUID>> addByUsername(String username, String createdBy) {
        return playerService.findByUsername(username).thenCompose(player -> player
                .map(value -> add(value.uuid(), createdBy).thenApply(ignored -> Optional.of(value.uuid())))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    public CompletableFuture<Void> add(UUID uuid, String createdBy) {
        return databaseExecutor.submit(() -> repository.add(uuid, createdBy)).thenRun(() -> cache.add(uuid));
    }

    public CompletableFuture<Void> remove(UUID uuid) {
        return databaseExecutor.submit(() -> repository.remove(uuid)).thenRun(() -> cache.remove(uuid));
    }

    public CompletableFuture<List<WhitelistEntry>> list() {
        return databaseExecutor.submit(repository::findAll);
    }

    public CompletableFuture<Optional<UUID>> findPlayerUuid(String username) {
        return playerService.findByUsername(username).thenApply(player -> player.map(Player::uuid));
    }

    public Set<UUID> cachedPlayers() {
        return cache.snapshot();
    }
}
