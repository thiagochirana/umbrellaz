package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.auth.AuthService;
import dev.chirana.umbrellaz.authorization.AdministratorRepository;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.PlayerRepository;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.whitelist.WhitelistCache;
import dev.chirana.umbrellaz.whitelist.WhitelistRepository;
import dev.chirana.umbrellaz.whitelist.WhitelistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationPersistenceTest {
    private final DatabaseExecutor databaseExecutor = new DatabaseExecutor();

    @AfterEach
    void closeExecutor() {
        databaseExecutor.close();
    }

    @Test
    void persistsWhitelistAndUpdatesRuntimeAuthorization(@TempDir Path directory) {
        SQLiteDatabase database = new SQLiteDatabase(directory.resolve("test.db"));
        MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
        databaseExecutor.submit(() -> database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        })).join();
        databaseExecutor.submit(() -> database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        })).join();
        long migrationCount = database.withConnection(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM _umbrellaz_migrations")) {
                result.next();
                return result.getLong(1);
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertEquals(4, migrationCount);

        PlayerService playerService = new PlayerService(new PlayerRepository(database), databaseExecutor);
        WhitelistService whitelistService = new WhitelistService(new WhitelistRepository(database), playerService, databaseExecutor, new WhitelistCache());
        AuthService authService = new AuthService(playerService, whitelistService);
        UUID uuid = UUID.randomUUID();

        whitelistService.loadCache().join();
        assertFalse(authService.onJoin(uuid, "Ada").join());
        playerService.recordJoin(uuid, "Ada").join();
        whitelistService.add(uuid, "operator").join();
        assertTrue(authService.onJoin(uuid, "Ada").join());
        whitelistService.remove(uuid).join();
        authService.block(uuid);
        assertTrue(authService.isBlocked(uuid));
    }

    @Test
    void failedWhitelistPersistenceDoesNotUpdateCache(@TempDir Path directory) throws Exception {
        Path databaseParent = directory.resolve("not-a-directory");
        Files.writeString(databaseParent, "occupied");
        SQLiteDatabase database = new SQLiteDatabase(databaseParent.resolve("umbrellaz.db"));
        WhitelistCache cache = new WhitelistCache();
        WhitelistService whitelistService = new WhitelistService(
                new WhitelistRepository(database),
                new PlayerService(new PlayerRepository(database), databaseExecutor),
                databaseExecutor,
                cache);
        UUID uuid = UUID.randomUUID();

        assertThrows(Exception.class, () -> whitelistService.add(uuid, "operator").join());
        assertFalse(cache.contains(uuid));
    }

    @Test
    void internalAdministratorTableControlsWhitelistAuthorization(@TempDir Path directory) {
        SQLiteDatabase database = new SQLiteDatabase(directory.resolve("test.db"));
        MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
        databaseExecutor.submit(() -> database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        })).join();

        UUID uuid = UUID.randomUUID();
        PlayerService playerService = new PlayerService(new PlayerRepository(database), databaseExecutor);
        playerService.recordJoin(uuid, "Ada").join();
        AuthorizationService authorizationService = new AuthorizationService(
                new AdministratorRepository(database), databaseExecutor);

        authorizationService.loadCache().join();
        assertFalse(authorizationService.isAdministrator(uuid));
        databaseExecutor.submit(() -> database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO _umbrellaz_administrators(player_uuid, created_at, created_by) VALUES (?, ?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, java.time.Instant.now().toString());
                statement.setString(3, "sql");
                statement.executeUpdate();
                return null;
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        })).join();

        authorizationService.loadCache().join();
        assertTrue(authorizationService.isAdministrator(uuid));
    }
}
