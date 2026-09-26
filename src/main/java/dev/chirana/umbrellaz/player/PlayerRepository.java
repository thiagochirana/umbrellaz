package dev.chirana.umbrellaz.player;

import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class PlayerRepository {
    private final SQLiteDatabase database;

    public PlayerRepository(SQLiteDatabase database) {
        this.database = database;
    }

    public void save(Player player) {
        database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO players(uuid, username, created_at, updated_at) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, updated_at = excluded.updated_at")) {
                statement.setString(1, player.uuid().toString());
                statement.setString(2, player.username());
                statement.setString(3, player.createdAt().toString());
                statement.setString(4, player.updatedAt().toString());
                statement.executeUpdate();
                return null;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to save player", exception);
            }
        });
    }

    public Optional<Player> findByUuid(UUID uuid) {
        return database.withConnection(connection -> find(connection, "SELECT uuid, username, created_at, updated_at FROM players WHERE uuid = ?", uuid.toString()));
    }

    public Optional<Player> findByUsername(String username) {
            return database.withConnection(connection -> find(connection, "SELECT uuid, username, created_at, updated_at FROM players WHERE lower(username) = lower(?) ORDER BY updated_at DESC LIMIT 1", username));
    }

    private Optional<Player> find(java.sql.Connection connection, String sql, String value) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Player(
                        UUID.fromString(resultSet.getString("uuid")),
                        resultSet.getString("username"),
                        Instant.parse(resultSet.getString("created_at")),
                        Instant.parse(resultSet.getString("updated_at"))));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to find player", exception);
        }
    }
}
