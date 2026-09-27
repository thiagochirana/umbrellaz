package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.AliasCache;
import dev.chirana.umbrellaz.player.AliasUpdate;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.PlayerRepository;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.player.PlayerResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAliasTest {
    private final DatabaseExecutor databaseExecutor = new DatabaseExecutor();

    @AfterEach
    void closeExecutor() {
        databaseExecutor.close();
    }

    @Test
    void cacheIsUnavailableUntilLoadedAndRejectsDuplicateAliases() {
        AliasCache cache = new AliasCache();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertFalse(cache.isReady());
        assertTrue(cache.find("ada").isEmpty());
        cache.replace(Map.of(first, "Ada_1"));
        assertTrue(cache.isReady());
        assertEquals(first, cache.find("ADA_1").orElseThrow());
        cache.put(first, "Bia_2");
        assertTrue(cache.find("Ada_1").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> cache.put(second, "BIA_2"));
        assertThrows(IllegalArgumentException.class, () -> cache.put(second, "bad-alias"));
    }

    @Test
    void migrationAddsAliasColumnAndUniqueIndexWithoutSeparateTable(@TempDir Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));

        boolean columnExists = database.withConnection(connection -> {
            try (var statement = connection.prepareStatement("PRAGMA table_info(players)")) {
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        if ("player_alias".equals(result.getString("name"))) {
                            return true;
                        }
                    }
                    return false;
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
        boolean indexExists = database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'idx_players_player_alias_unique'")) {
                try (var result = statement.executeQuery()) {
                    return result.next();
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });

        boolean tableExists = database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name LIKE '%alias%'")) {
                try (var result = statement.executeQuery()) {
                    return result.next();
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertTrue(columnExists);
        assertTrue(indexExists);
        assertFalse(tableExists);
    }

    @Test
    void aliasesAreValidatedAndLaterUsernameCollisionIsAmbiguous(@TempDir Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));
        PlayerRepository repository = new PlayerRepository(database);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        savePlayer(repository, first, "Ada");
        savePlayer(repository, second, "Bia");

        assertEquals(AliasUpdate.Status.INVALID_ALIAS, repository.setAlias(first, "bad-alias").status());
        assertEquals(AliasUpdate.Status.RESERVED_ALIAS, repository.setAlias(first, "SELF").status());
        assertEquals(AliasUpdate.Status.UPDATED, repository.setAlias(first, "Upper_1").status());
        assertEquals("upper_1", database.withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT player_alias FROM players WHERE uuid = ?")) {
                statement.setString(1, first.toString());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }));

        Instant now = Instant.now();
        repository.save(new Player(second, "Upper_1", now, now, null));
        assertEquals(PlayerResolution.Status.AMBIGUOUS, repository.findByIdentifier("UPPER_1").status());
        assertEquals(AliasUpdate.Status.USERNAME_CONFLICT, repository.setAlias(first, "upper_1").status());
    }

    @Test
    void aliasesAreCaseInsensitiveAndReplacementKeepsOneAlias(@TempDir Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));
        PlayerRepository repository = new PlayerRepository(database);
        UUID uuid = UUID.randomUUID();
        savePlayer(repository, uuid, "Ada");

        assertEquals(AliasUpdate.Status.UPDATED, repository.setAlias(uuid, "Eros").status());
        assertEquals(uuid, repository.findByIdentifier("EROS").player().uuid());
        assertEquals(AliasUpdate.Status.UPDATED, repository.setAlias(uuid, "Luna").status());
        assertEquals(PlayerResolution.Status.NOT_FOUND, repository.findByIdentifier("eros").status());
        assertEquals(uuid, repository.findByIdentifier("LUNA").player().uuid());

        long aliases = database.withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM players WHERE uuid = ? AND player_alias IS NOT NULL")) {
                statement.setString(1, uuid.toString());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertEquals(1, aliases);
    }

    @Test
    void aliasesAreGloballyUnique(@TempDir Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));
        PlayerRepository repository = new PlayerRepository(database);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        savePlayer(repository, first, "Ada");
        savePlayer(repository, second, "Bia");

        assertEquals(AliasUpdate.Status.UPDATED, repository.setAlias(first, "Eros").status());
        assertEquals(AliasUpdate.Status.CONFLICT, repository.setAlias(second, "eRoS").status());
        assertEquals(first, repository.findByIdentifier("eros").player().uuid());
    }

    @Test
    void serviceUpdatesCacheOnlyAfterSuccessfulPersistenceAndResolvesAliasesFirst(@TempDir Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));
        PlayerRepository repository = new PlayerRepository(database);
        AliasCache cache = new AliasCache();
        PlayerService service = new PlayerService(repository, databaseExecutor, cache);
        UUID usernameOwner = UUID.randomUUID();
        UUID aliasOwner = UUID.randomUUID();
        UUID conflictOwner = UUID.randomUUID();
        savePlayer(repository, usernameOwner, "Eros");
        savePlayer(repository, aliasOwner, "Alena");
        savePlayer(repository, conflictOwner, "Bia");
        service.loadAliasCache().join();

        assertEquals(AliasUpdate.Status.USERNAME_CONFLICT, service.setAlias("Alena", "Eros").join().status());
        assertEquals(AliasUpdate.Status.UPDATED, service.setAlias("Alena", "Nox").join().status());
        assertEquals(aliasOwner, cache.find("nox").orElseThrow());
        assertEquals(aliasOwner, service.findByIdentifier("nox").join().player().uuid());
        assertEquals(AliasUpdate.Status.CONFLICT, service.setAlias("Bia", "Nox").join().status());
        assertEquals(AliasUpdate.Status.UPDATED, service.setAlias("Alena", "Luna").join().status());
        assertTrue(cache.find("nox").isEmpty());
        assertEquals(aliasOwner, cache.find("luna").orElseThrow());
        assertFalse(cache.find("Alena").isPresent());
    }

    private SQLiteDatabase migratedDatabase(Path databasePath) {
        SQLiteDatabase database = new SQLiteDatabase(databasePath);
        MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
        databaseExecutor.submit(() -> database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        })).join();
        return database;
    }

    private void savePlayer(PlayerRepository repository, UUID uuid, String username) {
        Instant now = Instant.now();
        repository.save(new Player(uuid, username, now, now, null));
    }
}
