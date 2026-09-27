package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.auth.AuthEvents;
import dev.chirana.umbrellaz.auth.AuthService;
import dev.chirana.umbrellaz.authorization.AdministratorRepository;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.blocks.BlocksCommand;
import dev.chirana.umbrellaz.blocks.BlocksEvents;
import dev.chirana.umbrellaz.blocks.BlocksService;
import dev.chirana.umbrellaz.config.ConfigLoader;
import dev.chirana.umbrellaz.config.UmbrellazConfig;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.PlayerRepository;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.player.PlayerStatusCommand;
import dev.chirana.umbrellaz.player.HealthLockEvents;
import dev.chirana.umbrellaz.player.HealthLockService;
import dev.chirana.umbrellaz.player.AliasCache;
import dev.chirana.umbrellaz.player.UserCommand;
import dev.chirana.umbrellaz.player.OnlinePlayerResolver;
import dev.chirana.umbrellaz.player.OnlinePlayerSuggestions;
import dev.chirana.umbrellaz.whitelist.WhitelistCache;
import dev.chirana.umbrellaz.whitelist.WhitelistCommand;
import dev.chirana.umbrellaz.whitelist.WhitelistRepository;
import dev.chirana.umbrellaz.whitelist.WhitelistService;
import dev.chirana.umbrellaz.reload.UmbrellazReloadCommand;
import dev.chirana.umbrellaz.reload.UmbrellazReloadService;
import dev.chirana.umbrellaz.teleport.TeleportCommand;
import dev.chirana.umbrellaz.world.WorldTimeCommand;
import dev.chirana.umbrellaz.world.WorldSkyCommand;
import dev.chirana.umbrellaz.world.WorldSkyService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class Umbrellaz implements ModInitializer {
    public static final String MOD_ID = "umbrellaz";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        UmbrellazConfig config = new ConfigLoader().load(configDirectory);
        DatabaseExecutor databaseExecutor = new DatabaseExecutor();
        SQLiteDatabase database = new SQLiteDatabase(configDirectory.resolve("umbrellaz.db"));
        MigrationRunner migrationRunner = new MigrationRunner(new MigrationLoader().load());
        PlayerRepository playerRepository = new PlayerRepository(database);
        AliasCache aliasCache = new AliasCache();
        PlayerService playerService = new PlayerService(playerRepository, databaseExecutor, aliasCache);
        WhitelistCache whitelistCache = new WhitelistCache();
        WhitelistRepository whitelistRepository = new WhitelistRepository(database);
        WhitelistService whitelistService = new WhitelistService(whitelistRepository, playerService, databaseExecutor, whitelistCache);
        AuthService authService = new AuthService(playerService, whitelistService);
        AdministratorRepository administratorRepository = new AdministratorRepository(database);
        AuthorizationService authorizationService = new AuthorizationService(administratorRepository, databaseExecutor);
        UmbrellazReloadService reloadService = new UmbrellazReloadService(
                playerService, whitelistService, authorizationService);

        databaseExecutor.submit(() -> database.withConnection(connection -> {
            migrationRunner.run(connection);
            return null;
        })).thenCompose(ignored -> playerService.loadAliasCache())
                .thenCompose(ignored -> whitelistService.loadCache())
                .thenCompose(ignored -> authorizationService.loadCache())
                .whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                LOGGER.error("Umbrellaz database initialization failed; authorization remains blocked", throwable);
            } else {
                LOGGER.info("Umbrellaz database initialized");
            }
        });

        OnlinePlayerResolver onlinePlayerResolver = new OnlinePlayerResolver(aliasCache);
        OnlinePlayerSuggestions onlinePlayerSuggestions = new OnlinePlayerSuggestions(aliasCache);
        WhitelistCommand whitelistCommand = new WhitelistCommand(
                whitelistService, authorizationService, authService, config, onlinePlayerSuggestions);
        UserCommand userCommand = new UserCommand(
                playerService, authorizationService, onlinePlayerSuggestions);
        TeleportCommand teleportCommand = new TeleportCommand(
                authorizationService, onlinePlayerResolver, onlinePlayerSuggestions);
        WorldTimeCommand worldTimeCommand = new WorldTimeCommand(authorizationService);
        WorldSkyCommand worldSkyCommand = new WorldSkyCommand(authorizationService, new WorldSkyService());
        BlocksService blocksService = new BlocksService();
        BlocksCommand blocksCommand = new BlocksCommand(blocksService, authorizationService);
        BlocksEvents.register(blocksService);
        HealthLockService healthLockService = new HealthLockService();
        HealthLockEvents.register(healthLockService);
        PlayerStatusCommand playerStatusCommand = new PlayerStatusCommand(
                authorizationService, healthLockService, onlinePlayerResolver, onlinePlayerSuggestions);
        UmbrellazReloadCommand reloadCommand = new UmbrellazReloadCommand(authorizationService, reloadService);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            whitelistCommand.register(dispatcher);
            userCommand.register(dispatcher);
            teleportCommand.register(dispatcher);
            worldTimeCommand.register(dispatcher);
            worldSkyCommand.register(dispatcher);
            blocksCommand.register(dispatcher);
            playerStatusCommand.register(dispatcher);
            reloadCommand.register(dispatcher);
        });
        AuthEvents.register(authService, authorizationService, config);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> databaseExecutor.close());
        LOGGER.info("Umbrellaz initialized");
    }
}
