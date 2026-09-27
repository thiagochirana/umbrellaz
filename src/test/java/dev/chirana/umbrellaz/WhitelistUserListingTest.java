package dev.chirana.umbrellaz;

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
import dev.chirana.umbrellaz.whitelist.WhitelistUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhitelistUserListingTest {
    private final DatabaseExecutor databaseExecutor = new DatabaseExecutor();

    @AfterEach
    void closeExecutor() {
        databaseExecutor.close();
    }

    @Test
    void classifiesKnownPlayersByWhitelistOnlyWithDeterministicOrderingAndAliases(@TempDir Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));
        PlayerRepository playerRepository = new PlayerRepository(database);
        UUID whitelistedUuid = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID firstUnlistedUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondUnlistedUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID blankUsernameUuid = UUID.fromString("00000000-0000-0000-0000-000000000004");
        Instant now = Instant.parse("2026-09-27T12:00:00Z");

        playerRepository.save(new Player(whitelistedUuid, "zeta", now, now, "AliasZ"));
        playerRepository.save(new Player(firstUnlistedUuid, "Alpha", now, now, null));
        playerRepository.save(new Player(secondUnlistedUuid, "alpha", now, now, null));
        playerRepository.save(new Player(blankUsernameUuid, "", now, now, null));
        database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO _umbrellaz_administrators(player_uuid, created_at, created_by) VALUES (?, ?, ?)")) {
                statement.setString(1, secondUnlistedUuid.toString());
                statement.setString(2, now.toString());
                statement.setString(3, "Console");
                statement.executeUpdate();
                return null;
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });

        WhitelistRepository whitelistRepository = new WhitelistRepository(database);
        whitelistRepository.add(whitelistedUuid, "Console");
        WhitelistService service = new WhitelistService(
                whitelistRepository,
                new PlayerService(playerRepository, databaseExecutor, new AliasCache()),
                databaseExecutor,
                new WhitelistCache());
        service.loadCache().join();

        List<WhitelistUser> users = service.listUsers().join();

        assertEquals(List.of(blankUsernameUuid, firstUnlistedUuid, secondUnlistedUuid, whitelistedUuid),
                users.stream().map(user -> user.player().uuid()).toList());
        assertEquals(List.of(blankUsernameUuid, firstUnlistedUuid, secondUnlistedUuid), users.stream()
                .filter(user -> !user.whitelisted())
                .map(user -> user.player().uuid())
                .toList());
        assertEquals(List.of(whitelistedUuid), users.stream()
                .filter(WhitelistUser::whitelisted)
                .map(user -> user.player().uuid())
                .toList());
        assertEquals("AliasZ", users.getLast().player().alias());
        assertEquals("", users.getFirst().player().username());
        assertNull(users.get(1).player().alias());
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
}
