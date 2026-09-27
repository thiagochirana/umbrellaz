package dev.chirana.umbrellaz.reload;

import dev.chirana.umbrellaz.authorization.AdministratorRepository;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.AliasCache;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.PlayerRepository;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.whitelist.WhitelistCache;
import dev.chirana.umbrellaz.whitelist.WhitelistRepository;
import dev.chirana.umbrellaz.whitelist.WhitelistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UmbrellazReloadServiceTest {
    private final DatabaseExecutor databaseExecutor = new DatabaseExecutor();

    @AfterEach
    void closeExecutor() {
        databaseExecutor.close();
    }

    @Test
    void reloadReplacesAliasWhitelistAndAdministratorCaches(@TempDir Path directory) {
        SQLiteDatabase database = new SQLiteDatabase(directory.resolve("test.db"));
        databaseExecutor.submit(() -> database.withConnection(connection -> {
            new MigrationRunner(new MigrationLoader().load()).run(connection);
            return null;
        })).join();

        PlayerRepository playerRepository = new PlayerRepository(database);
        WhitelistRepository whitelistRepository = new WhitelistRepository(database);
        UUID oldPlayer = UUID.randomUUID();
        UUID newPlayer = UUID.randomUUID();
        Instant now = Instant.now();
        playerRepository.save(new Player(oldPlayer, "OldPlayer", now, now, "oldalias"));
        playerRepository.save(new Player(newPlayer, "NewPlayer", now, now, null));
        whitelistRepository.add(oldPlayer, "test");
        insertAdministrator(database, oldPlayer);

        AliasCache aliasCache = new AliasCache();
        WhitelistCache whitelistCache = new WhitelistCache();
        PlayerService playerService = new PlayerService(playerRepository, databaseExecutor, aliasCache);
        WhitelistService whitelistService = new WhitelistService(
                whitelistRepository, playerService, databaseExecutor, whitelistCache);
        AuthorizationService authorizationService = new AuthorizationService(
                new AdministratorRepository(database), databaseExecutor);
        UmbrellazReloadService reloadService = new UmbrellazReloadService(
                playerService, whitelistService, authorizationService);

        reloadService.reload().join();
        assertEquals(oldPlayer, aliasCache.find("oldalias").orElseThrow());
        assertTrue(whitelistCache.contains(oldPlayer));
        assertTrue(authorizationService.isAdministrator(oldPlayer));

        playerRepository.setAlias(oldPlayer, "changedalias");
        playerRepository.setAlias(newPlayer, "newalias");
        whitelistRepository.remove(oldPlayer);
        whitelistRepository.add(newPlayer, "test");
        replaceAdministrator(database, oldPlayer, newPlayer);

        reloadService.reload().join();

        assertTrue(aliasCache.find("oldalias").isEmpty());
        assertEquals(newPlayer, aliasCache.find("newalias").orElseThrow());
        assertFalse(whitelistCache.contains(oldPlayer));
        assertTrue(whitelistCache.contains(newPlayer));
        assertFalse(authorizationService.isAdministrator(oldPlayer));
        assertTrue(authorizationService.isAdministrator(newPlayer));
    }

    private void insertAdministrator(SQLiteDatabase database, UUID playerUuid) {
        database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO _umbrellaz_administrators(player_uuid, created_at, created_by) VALUES (?, ?, ?)")) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, Instant.now().toString());
                statement.setString(3, "test");
                statement.executeUpdate();
                return null;
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private void replaceAdministrator(SQLiteDatabase database, UUID oldPlayer, UUID newPlayer) {
        database.withConnection(connection -> {
            try (var delete = connection.prepareStatement(
                    "DELETE FROM _umbrellaz_administrators WHERE player_uuid = ?")) {
                delete.setString(1, oldPlayer.toString());
                delete.executeUpdate();
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
            return null;
        });
        insertAdministrator(database, newPlayer);
    }
}
