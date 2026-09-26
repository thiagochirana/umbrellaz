package dev.chirana.umbrellaz.authorization;

import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class AdministratorRepository {
    private static final String TABLE = "_umbrellaz_administrators";
    private final SQLiteDatabase database;

    public AdministratorRepository(SQLiteDatabase database) {
        this.database = database;
    }

    public boolean exists(UUID playerUuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM " + TABLE + " WHERE player_uuid = ?")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to check Umbrellaz administrator", exception);
            }
        });
    }

    public Set<UUID> findAllUuids() {
        return database.withConnection(connection -> {
            Set<UUID> playerUuids = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM " + TABLE);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    playerUuids.add(UUID.fromString(resultSet.getString(1)));
                }
                return playerUuids;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to load Umbrellaz administrators", exception);
            }
        });
    }
}
