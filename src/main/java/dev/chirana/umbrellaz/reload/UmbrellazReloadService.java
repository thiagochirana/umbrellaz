package dev.chirana.umbrellaz.reload;

import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.whitelist.WhitelistService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class UmbrellazReloadService {
    private final PlayerService playerService;
    private final WhitelistService whitelistService;
    private final AuthorizationService authorizationService;

    public UmbrellazReloadService(
            PlayerService playerService,
            WhitelistService whitelistService,
            AuthorizationService authorizationService) {
        this.playerService = playerService;
        this.whitelistService = whitelistService;
        this.authorizationService = authorizationService;
    }

    public CompletableFuture<Void> reload() {
        return load("aliases", playerService::loadAliasCache)
                .thenCompose(ignored -> load("whitelist", whitelistService::loadCache))
                .thenCompose(ignored -> load("administradores", authorizationService::loadCache));
    }

    private CompletableFuture<Void> load(String cacheName, Supplier<CompletableFuture<Void>> operation) {
        try {
            return operation.get().handle((ignored, throwable) -> {
                if (throwable != null) {
                    throw new CompletionException("Falha ao recarregar o cache de " + cacheName, cause(throwable));
                }
                return null;
            });
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(
                    new CompletionException("Falha ao recarregar o cache de " + cacheName, cause(throwable)));
        }
    }

    private Throwable cause(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
    }
}
