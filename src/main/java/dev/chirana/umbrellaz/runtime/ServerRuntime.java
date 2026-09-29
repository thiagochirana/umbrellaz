package dev.chirana.umbrellaz.runtime;

import dev.chirana.umbrellaz.auth.AuthService;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.blocks.BlocksCommand;
import dev.chirana.umbrellaz.blocks.BlocksService;
import dev.chirana.umbrellaz.config.UmbrellazConfig;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.lock.LockEvents;
import dev.chirana.umbrellaz.lock.LockRepository;
import dev.chirana.umbrellaz.lock.LockService;
import dev.chirana.umbrellaz.player.HealthLockService;
import dev.chirana.umbrellaz.player.PlayerStatusCommand;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.player.OnlinePlayerResolver;
import dev.chirana.umbrellaz.player.OnlinePlayerSuggestions;
import dev.chirana.umbrellaz.player.UserCommand;
import dev.chirana.umbrellaz.protocol.ProtocolSessionManager;
import dev.chirana.umbrellaz.reload.UmbrellazReloadCommand;
import dev.chirana.umbrellaz.reload.UmbrellazReloadService;
import dev.chirana.umbrellaz.teleport.TeleportCommand;
import dev.chirana.umbrellaz.whitelist.WhitelistCommand;
import dev.chirana.umbrellaz.whitelist.WhitelistService;
import dev.chirana.umbrellaz.world.WorldSkyCommand;
import dev.chirana.umbrellaz.world.WorldSkyService;
import dev.chirana.umbrellaz.world.WorldTimeCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final UmbrellazConfig config;
    private final DatabaseExecutor databaseExecutor;
    private final dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase database;
    private final PlayerService playerService;
    private final WhitelistService whitelistService;
    private final AuthService authService;
    private final AuthorizationService authorizationService;
    private final LockService lockService;
    private final LockRepository lockRepository;
    private final LockEvents lockEvents;
    private final BlocksService blocksService;
    private final HealthLockService healthLockService;
    private final ProtocolSessionManager protocol;
    private final UmbrellazReloadService reloadService;
    private final WhitelistCommand whitelistCommand;
    private final UserCommand userCommand;
    private final TeleportCommand teleportCommand;
    private final WorldTimeCommand worldTimeCommand;
    private final WorldSkyCommand worldSkyCommand;
    private final BlocksCommand blocksCommand;
    private final PlayerStatusCommand playerStatusCommand;
    private final UmbrellazReloadCommand reloadCommand;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    ServerRuntime(MinecraftServer server, UmbrellazConfig config, DatabaseExecutor databaseExecutor,
                  dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase database,
                  PlayerService playerService, WhitelistService whitelistService, AuthService authService,
                  AuthorizationService authorizationService, LockService lockService,
                  LockRepository lockRepository, LockEvents lockEvents, BlocksService blocksService,
                  HealthLockService healthLockService, ProtocolSessionManager protocol,
                  UmbrellazReloadService reloadService, WhitelistCommand whitelistCommand,
                  UserCommand userCommand, TeleportCommand teleportCommand, WorldTimeCommand worldTimeCommand,
                  WorldSkyCommand worldSkyCommand, BlocksCommand blocksCommand,
                  PlayerStatusCommand playerStatusCommand, UmbrellazReloadCommand reloadCommand) {
        this.server = server;
        this.config = config;
        this.databaseExecutor = databaseExecutor;
        this.database = database;
        this.playerService = playerService;
        this.whitelistService = whitelistService;
        this.authService = authService;
        this.authorizationService = authorizationService;
        this.lockService = lockService;
        this.lockRepository = lockRepository;
        this.lockEvents = lockEvents;
        this.blocksService = blocksService;
        this.healthLockService = healthLockService;
        this.protocol = protocol;
        this.reloadService = reloadService;
        this.whitelistCommand = whitelistCommand;
        this.userCommand = userCommand;
        this.teleportCommand = teleportCommand;
        this.worldTimeCommand = worldTimeCommand;
        this.worldSkyCommand = worldSkyCommand;
        this.blocksCommand = blocksCommand;
        this.playerStatusCommand = playerStatusCommand;
        this.reloadCommand = reloadCommand;
    }

    public CompletableFuture<Void> start(dev.chirana.umbrellaz.infra.db.MigrationRunner migrationRunner) {
        try {
            lockEvents.initialize(server);
        } catch (Throwable failure) {
            CompletableFuture<Void> startupFailure = CompletableFuture.failedFuture(failure);
            startupFailure.whenComplete((ignored, ignoredFailure) -> startupFailed());
            return startupFailure;
        }
        CompletableFuture<Void> startup = databaseExecutor.submit(() -> database.withConnection(connection -> {
            migrationRunner.run(connection);
            return null;
        })).thenCompose(ignored -> playerService.loadAliasCache())
                .thenCompose(ignored -> whitelistService.loadCache())
                .thenCompose(ignored -> authorizationService.loadCache())
                .thenCompose(ignored -> lockEvents.loadPlacementSnapshotForRuntime())
                .thenCompose(ignored -> lockService.loadCache(lockRepository, databaseExecutor))
                .thenCompose(ignored -> dispatchToServer(() -> {
                    lockEvents.reconcileMarkers(server);
                    if (stopping.get()) {
                        throw new IllegalStateException("Umbrellaz runtime stopped during startup");
                    }
                    ready.set(true);
                }));
        startup.whenComplete((ignored, failure) -> {
            if (failure != null) {
                startupFailed();
            }
        });
        return startup;
    }

    private CompletableFuture<Void> dispatchToServer(Runnable operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            server.execute(() -> {
                if (stopping.get()) {
                    result.completeExceptionally(new IllegalStateException("Umbrellaz runtime is unavailable"));
                    return;
                }
                try {
                    operation.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public void onJoin(net.minecraft.server.level.ServerPlayer player) {
        if (stopping.get()) {
            return;
        }
        protocol.onJoin(player, server);
        authService.onJoin(player.getUUID(), player.getName().getString()).thenAccept(authenticated ->
                server.execute(() -> {
                    if (!ready.get() || stopping.get()) {
                        return;
                    }
                    if (!authenticated && player.isAlive()) {
                        dev.chirana.umbrellaz.auth.AuthEvents.teleportToRestrictedSpawn(server, player, config);
                    }
                }));
    }

    public void onDisconnect(net.minecraft.server.level.ServerPlayer player) {
        protocol.onDisconnect(player);
        authService.onDisconnect(player.getUUID());
        lockEvents.disconnect(server, player);
        healthLockService.clear(player.getUUID());
    }

    public void tick() {
        protocol.tick(server);
        lockEvents.tick(server);
    }

    public boolean isInteractionAllowed(net.minecraft.server.level.ServerPlayer player) {
        return ready.get() && !stopping.get() && authService.isAuthenticated(player.getUUID())
                && protocol.isCompatible(player);
    }

    public void registerCommands() {
        if (!ready.get() || stopping.get()) {
            return;
        }
        whitelistCommand.register(server.getCommands().getDispatcher());
        userCommand.register(server.getCommands().getDispatcher());
        teleportCommand.register(server.getCommands().getDispatcher());
        worldTimeCommand.register(server.getCommands().getDispatcher());
        worldSkyCommand.register(server.getCommands().getDispatcher());
        blocksCommand.register(server.getCommands().getDispatcher());
        playerStatusCommand.register(server.getCommands().getDispatcher());
        reloadCommand.register(server.getCommands().getDispatcher());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    public void stop() {
        stopping.set(true);
        ready.set(false);
        if (!shutdownStarted.compareAndSet(false, true)) return;
        ServerRuntimeRegistry.remove(server, this);
        protocol.shutdown();
        lockEvents.shutdownReservations(server);
        dev.chirana.umbrellaz.lock.LockWorldIdentity.clear(server);
        lockService.resetOnRestart();
        lockService.close();
        databaseExecutor.close();
    }

    void startupFailed() {
        stopping.set(true);
        ready.set(false);
        dev.chirana.umbrellaz.lock.LockWorldIdentity.clear(server);
        try {
            server.execute(this::stop);
        } catch (Throwable failure) {
            stop();
        }
    }

    @Override public void close() { stop(); }
    public boolean ready() { return ready.get(); }
    public boolean stopping() { return stopping.get(); }
    public MinecraftServer server() { return server; }
    public UmbrellazConfig config() { return config; }
    public AuthService authService() { return authService; }
    public AuthorizationService authorizationService() { return authorizationService; }
    public LockEvents lockEvents() { return lockEvents; }
    public BlocksService blocksService() { return blocksService; }
    public HealthLockService healthLockService() { return healthLockService; }
    public ProtocolSessionManager protocol() { return protocol; }
}
