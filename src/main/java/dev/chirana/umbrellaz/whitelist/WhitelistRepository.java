package dev.chirana.umbrellaz.whitelist;

import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WhitelistRepository {
    private final SQLiteDatabase database;

    public WhitelistRepository(SQLiteDatabase database) {
        this.database = database;
    }

    public void add(UUID uuid, String createdBy) {
        database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO whitelist(player_uuid, created_at, created_by) VALUES (?, ?, ?) "
                            + "ON CONFLICT(player_uuid) DO NOTHING")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, Instant.now().toString());
                statement.setString(3, createdBy);
                statement.executeUpdate();
                return null;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to add whitelist entry", exception);
            }
        });
    }

    public void remove(UUID uuid) {
        database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM whitelist WHERE player_uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
                return null;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to remove whitelist entry", exception);
            }
        });
    }

    public Set<UUID> findAllUuids() {
        return database.withConnection(connection -> {
            Set<UUID> uuids = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM whitelist");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    uuids.add(UUID.fromString(resultSet.getString(1)));
                }
                return uuids;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to list whitelist entries", exception);
            }
        });
    }

    public List<WhitelistEntry> findAll() {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT w.player_uuid, p.username, w.created_at, w.created_by "
                            + "FROM whitelist w LEFT JOIN players p ON p.uuid = w.player_uuid ORDER BY w.created_at");
                  ResultSet resultSet = statement.executeQuery()) {
                List<WhitelistEntry> entries = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    entries.add(new WhitelistEntry(
                            UUID.fromString(resultSet.getString("player_uuid")),
                            resultSet.getString("username"),
                            Instant.parse(resultSet.getString("created_at")),
                            resultSet.getString("created_by")));
                }
                return entries;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to list whitelist entries", exception);
            }
        });
    }
}
