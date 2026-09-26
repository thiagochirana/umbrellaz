package dev.chirana.umbrellaz.authorization;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AuthorizationService {
    private final AdministratorRepository administratorRepository;
    private final DatabaseExecutor databaseExecutor;
    private final AdministratorCache administratorCache = new AdministratorCache();

    public AuthorizationService(AdministratorRepository administratorRepository, DatabaseExecutor databaseExecutor) {
        this.administratorRepository = administratorRepository;
        this.databaseExecutor = databaseExecutor;
    }

    public CompletableFuture<Boolean> mayManageWhitelist(UUID playerUuid) {
        return CompletableFuture.completedFuture(isAdministrator(playerUuid));
    }

    public CompletableFuture<Void> loadCache() {
        return databaseExecutor.submit(administratorRepository::findAllUuids)
                .thenAccept(administratorCache::replace);
    }

    public boolean isAdministrator(UUID playerUuid) {
        return administratorCache.contains(playerUuid);
    }
}
