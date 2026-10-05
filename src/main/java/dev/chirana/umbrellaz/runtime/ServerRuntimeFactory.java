package dev.chirana.umbrellaz.runtime;

import dev.chirana.umbrellaz.auth.AuthService;
import dev.chirana.umbrellaz.audit.AuditCommand;
import dev.chirana.umbrellaz.audit.AuditRepository;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.AuditWriter;
import dev.chirana.umbrellaz.audit.DamageAuditAggregator;
import dev.chirana.umbrellaz.authorization.AdministratorRepository;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.blocks.BlocksCommand;
import dev.chirana.umbrellaz.blocks.BlocksService;
import dev.chirana.umbrellaz.config.ConfigLoader;
import dev.chirana.umbrellaz.config.UmbrellazConfig;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.lock.*;
import dev.chirana.umbrellaz.player.*;
import dev.chirana.umbrellaz.protocol.ProtocolConstants;
import dev.chirana.umbrellaz.protocol.ProtocolSessionManager;
import dev.chirana.umbrellaz.reload.UmbrellazReloadCommand;
import dev.chirana.umbrellaz.reload.UmbrellazReloadService;
import dev.chirana.umbrellaz.teleport.TeleportCommand;
import dev.chirana.umbrellaz.whitelist.*;
import dev.chirana.umbrellaz.world.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

public final class ServerRuntimeFactory {
    public ServerRuntime create(MinecraftServer server) {
        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve("umbrellaz");
        UmbrellazConfig config = new ConfigLoader().load(configDirectory);
        DatabaseExecutor executor = new DatabaseExecutor();
        AuditWriter auditWriter = null;
        try {
            SQLiteDatabase database = new SQLiteDatabase(configDirectory.resolve("umbrellaz.db"));
            AuditRepository auditRepository = new AuditRepository(database);
            auditWriter = new AuditWriter(executor, auditRepository);
            AuditService auditService = new AuditService(UUID.randomUUID(), executor, auditRepository, auditWriter);
            DamageAuditAggregator damageAuditAggregator = new DamageAuditAggregator(auditService);
            PlayerRepository playerRepository = new PlayerRepository(database);
            AliasCache aliasCache = new AliasCache();
            PlayerService playerService = new PlayerService(playerRepository, executor, aliasCache);
            WhitelistCache whitelistCache = new WhitelistCache();
            WhitelistRepository whitelistRepository = new WhitelistRepository(database);
            WhitelistService whitelistService = new WhitelistService(whitelistRepository, playerService, executor, whitelistCache);
            AuthService authService = new AuthService(playerService, whitelistService, auditService);
            AuthorizationService authorizationService = new AuthorizationService(new AdministratorRepository(database), executor);
            LockRepository lockRepository = new LockRepository(database);
            LockCache lockCache = new LockCache();
            LockService lockService = new LockService(new PasswordPolicy(), new PasswordKdf(), new LockPlacementPolicy(),
                    new AttemptLimiter(), lockCache);
             BlocksService blocksService = new BlocksService();
             HealthLockService healthLockService = new HealthLockService();
             FoodLockService foodLockService = new FoodLockService();
            ProtocolSessionManager protocol = new ProtocolSessionManager(Set.of(ProtocolConstants.FEATURE_LOCK_GUI));
            LockEvents lockEvents = new LockEvents(lockService, lockRepository, executor, authorizationService, protocol,
                    auditService);
            UmbrellazReloadService reloadService = new UmbrellazReloadService(playerService, whitelistService, authorizationService);
            OnlinePlayerResolver resolver = new OnlinePlayerResolver(aliasCache);
            OnlinePlayerSuggestions suggestions = new OnlinePlayerSuggestions(aliasCache);
            return new ServerRuntime(server, config, executor, database, playerService, whitelistService, authService,
                      authorizationService, lockService, lockRepository, lockEvents, blocksService,
                      healthLockService,
                      foodLockService,
                    protocol, reloadService,
                     new WhitelistCommand(whitelistService, authorizationService, authService, config, suggestions,
                             auditService),
                     new UserCommand(playerService, authorizationService, suggestions),
                     new TeleportCommand(authorizationService, resolver, suggestions, auditService),
                     new WorldTimeCommand(authorizationService, auditService),
                     new WorldSkyCommand(authorizationService, new WorldSkyService(), auditService),
                     new BlocksCommand(blocksService, authorizationService, auditService),
                        new PlayerStatusCommand(authorizationService, healthLockService, foodLockService, resolver,
                              suggestions, auditService),
                      new UmbrellazReloadCommand(authorizationService, reloadService, auditService), auditWriter, auditService,
                      new AuditCommand(auditService, authorizationService), damageAuditAggregator);
        } catch (RuntimeException | Error failure) {
            if (auditWriter == null) {
                executor.close();
            } else {
                auditWriter.shutdown().whenComplete((ignored, ignoredFailure) -> executor.close());
            }
            throw failure;
        }
    }

    public ServerRuntime start(MinecraftServer server) {
        ServerRuntime runtime = create(server);
        ServerRuntimeRegistry.put(server, runtime);
        try {
            runtime.start(new MigrationRunner(new MigrationLoader().load()))
                    .thenRun(runtime::registerCommands)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            org.slf4j.LoggerFactory.getLogger(ServerRuntimeFactory.class)
                                    .error("Umbrellaz runtime initialization failed; access remains blocked", failure);
                            runtime.startupFailed();
                        }
                    });
        } catch (Throwable failure) {
            org.slf4j.LoggerFactory.getLogger(ServerRuntimeFactory.class)
                    .error("Umbrellaz runtime initialization failed; access remains blocked", failure);
            runtime.startupFailed();
        }
        return runtime;
    }

    public void stop(MinecraftServer server) {
        ServerRuntimeRegistry.find(server).ifPresent(runtime -> {
            runtime.stop();
        });
    }
}
