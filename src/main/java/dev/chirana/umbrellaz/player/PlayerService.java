package dev.chirana.umbrellaz.player;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerService {
    private final PlayerRepository repository;
    private final DatabaseExecutor databaseExecutor;

    public PlayerService(PlayerRepository repository, DatabaseExecutor databaseExecutor) {
        this.repository = repository;
        this.databaseExecutor = databaseExecutor;
    }

    public CompletableFuture<Void> recordJoin(UUID uuid, String username) {
        Instant now = Instant.now();
        return databaseExecutor.submit(() -> {
            Optional<Player> existing = repository.findByUuid(uuid);
            repository.save(new Player(uuid, username, existing.map(Player::createdAt).orElse(now), now));
        });
    }

    public CompletableFuture<Optional<Player>> findByUsername(String username) {
        return databaseExecutor.submit(() -> repository.findByUsername(username));
    }
}
