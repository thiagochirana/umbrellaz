package dev.chirana.umbrellaz.whitelist;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.player.PlayerResolution;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.PlayerService;

import java.util.Comparator;
import java.util.List;
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

    public CompletableFuture<PlayerResolution> addByUsername(String username, String createdBy) {
        return playerService.findByIdentifier(username).thenCompose(resolution -> {
            if (resolution.status() != PlayerResolution.Status.FOUND) {
                return CompletableFuture.completedFuture(resolution);
            }
            return add(resolution.player().uuid(), createdBy)
                    .thenApply(ignored -> resolution);
        });
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

    public CompletableFuture<List<WhitelistUser>> listUsers() {
        return cacheReady.thenCompose(ignored -> playerService.findAll())
                .thenApply(players -> {
                    Set<UUID> whitelisted = cache.snapshot();
                    return players.stream()
                            .map(player -> new WhitelistUser(player, whitelisted.contains(player.uuid())))
                            .sorted(Comparator
                                    .comparing((WhitelistUser user) -> usernameSortKey(user.player()), String.CASE_INSENSITIVE_ORDER)
                                    .thenComparing(user -> user.player().uuid()))
                            .toList();
                });
    }

    private String usernameSortKey(Player player) {
        if (player.username() == null) {
            return "";
        }
        return player.username();
    }

    public CompletableFuture<PlayerResolution> findPlayerUuid(String username) {
        return playerService.findByIdentifier(username);
    }

    public Set<UUID> cachedPlayers() {
        return cache.snapshot();
    }
}
