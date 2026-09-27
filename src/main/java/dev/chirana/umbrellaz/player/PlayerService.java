package dev.chirana.umbrellaz.player;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerService {
    private final PlayerRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final AliasCache aliasCache;

    public PlayerService(PlayerRepository repository, DatabaseExecutor databaseExecutor) {
        this(repository, databaseExecutor, new AliasCache());
    }

    public PlayerService(PlayerRepository repository, DatabaseExecutor databaseExecutor, AliasCache aliasCache) {
        this.repository = repository;
        this.databaseExecutor = databaseExecutor;
        this.aliasCache = aliasCache;
    }

    public CompletableFuture<Void> recordJoin(UUID uuid, String username) {
        Instant now = Instant.now();
        return databaseExecutor.submit(() -> {
            Optional<Player> existing = repository.findByUuid(uuid);
            repository.save(new Player(
                    uuid,
                    username,
                    existing.map(Player::createdAt).orElse(now),
                    now,
                    existing.map(Player::alias).orElse(null)));
        });
    }

    public CompletableFuture<PlayerResolution> findByIdentifier(String identifier) {
        return aliasCache.whenReady().thenCompose(ignored -> databaseExecutor.submit(() -> repository.findByIdentifier(identifier)));
    }

    public CompletableFuture<List<Player>> findAll() {
        return databaseExecutor.submit(repository::findAll);
    }

    public CompletableFuture<Void> loadAliasCache() {
        return databaseExecutor.submit(repository::findAllAliases)
                .thenAccept(aliasCache::replace)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        aliasCache.fail();
                    }
                });
    }

    public Optional<UUID> findCachedAlias(String identifier) {
        return aliasCache.find(identifier);
    }

    public CompletableFuture<AliasUpdate> setAlias(String identifier, String alias) {
        if (AliasRules.isReserved(alias)) {
            return CompletableFuture.completedFuture(AliasUpdate.reservedAlias());
        }
        if (AliasRules.canonicalize(alias).isEmpty()) {
            return CompletableFuture.completedFuture(AliasUpdate.invalidAlias());
        }
        String canonical = AliasRules.canonicalize(alias).orElseThrow();
        return aliasCache.whenReady().thenCompose(ignored -> databaseExecutor.submit(() -> {
                    PlayerResolution resolution = repository.findByIdentifier(identifier);
                    return switch (resolution.status()) {
                        case NOT_FOUND -> AliasUpdate.unknownPlayer();
                        case AMBIGUOUS -> AliasUpdate.ambiguousPlayer();
                        case FOUND -> repository.setAlias(resolution.player().uuid(), canonical);
                        case NOT_READY -> AliasUpdate.notReady();
                    };
                }))
                .thenApply(update -> {
                    if (update.status() == AliasUpdate.Status.UPDATED) {
                        aliasCache.put(update.playerUuid(), update.alias());
                    }
                    return update;
                });
    }
}
