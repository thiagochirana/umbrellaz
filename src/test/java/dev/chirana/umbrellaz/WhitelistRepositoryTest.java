package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.PlayerRepository;
import dev.chirana.umbrellaz.whitelist.WhitelistEntry;
import dev.chirana.umbrellaz.whitelist.WhitelistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhitelistRepositoryTest {
    @Test
    void listsStoredPlayerUsernameWithWhitelistDetails(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("test.db"));
        UUID playerUuid = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-09-26T18:30:00Z");
        new PlayerRepository(database).save(new Player(playerUuid, "Ada", recordedAt, recordedAt, null));
        WhitelistRepository repository = new WhitelistRepository(database);

        repository.add(playerUuid, "Console");

        List<WhitelistEntry> entries = repository.findAll();
        assertEquals(1, entries.size());
        WhitelistEntry entry = entries.getFirst();
        assertEquals(playerUuid, entry.playerUuid());
        assertEquals("Ada", entry.username());
        assertEquals("Console", entry.createdBy());
    }

    @Test
    void keepsWhitelistEntryWhenPlayerMetadataIsMissing(@TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path databasePath = directory.resolve("test.db");
        SQLiteDatabase database = migratedDatabase(databasePath);
        UUID playerUuid = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-26T18:30:00Z");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             var statement = connection.prepareStatement(
                     "INSERT INTO whitelist(player_uuid, created_at, created_by) VALUES (?, ?, ?)")) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, createdAt.toString());
            statement.setString(3, "Console");
            statement.executeUpdate();
        }

        List<WhitelistEntry> entries = new WhitelistRepository(database).findAll();
        assertEquals(1, entries.size());
        WhitelistEntry entry = entries.getFirst();
        assertEquals(playerUuid, entry.playerUuid());
        assertNull(entry.username());
        assertEquals(createdAt, entry.createdAt());
        assertEquals("Console", entry.createdBy());
    }

    private SQLiteDatabase migratedDatabase(java.nio.file.Path databasePath) {
        SQLiteDatabase database = new SQLiteDatabase(databasePath);
        MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
        database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        });
        return database;
    }
}
