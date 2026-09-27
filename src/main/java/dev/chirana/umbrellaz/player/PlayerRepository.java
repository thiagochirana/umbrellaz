package dev.chirana.umbrellaz.player;

import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PlayerRepository {
    private final SQLiteDatabase database;

    public PlayerRepository(SQLiteDatabase database) {
        this.database = database;
    }

    public void save(Player player) {
        database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO players(uuid, username, created_at, updated_at, player_alias) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, updated_at = excluded.updated_at, "
                            + "player_alias = excluded.player_alias")) {
                statement.setString(1, player.uuid().toString());
                statement.setString(2, player.username());
                statement.setString(3, player.createdAt().toString());
                statement.setString(4, player.updatedAt().toString());
                statement.setString(5, player.alias());
                statement.executeUpdate();
                return null;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to save player", exception);
            }
        });
    }

    public Optional<Player> findByUuid(UUID uuid) {
        return database.withConnection(connection -> find(connection,
                "SELECT uuid, username, created_at, updated_at, player_alias FROM players WHERE uuid = ?",
                uuid.toString()));
    }

    public List<Player> findAll() {
        return database.withConnection(connection -> {
            List<Player> players = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, username, created_at, updated_at, player_alias FROM players");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    players.add(player(resultSet));
                }
                return players;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to list players", exception);
            }
        });
    }

    public PlayerResolution findByIdentifier(String identifier) {
        return database.withConnection(connection -> {
            Map<UUID, Player> matches = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, username, created_at, updated_at, player_alias FROM players "
                            + "WHERE username = ? COLLATE NOCASE OR player_alias = ? COLLATE NOCASE")) {
                statement.setString(1, identifier);
                statement.setString(2, identifier);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Player player = player(resultSet);
                        matches.put(player.uuid(), player);
                    }
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to find player", exception);
            }
            if (matches.isEmpty()) {
                return PlayerResolution.notFound();
            }
            return matches.size() == 1
                    ? PlayerResolution.found(matches.values().iterator().next())
                    : PlayerResolution.ambiguous();
        });
    }

    public Map<UUID, String> findAllAliases() {
        return database.withConnection(connection -> {
            Map<UUID, String> aliases = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, player_alias FROM players WHERE player_alias IS NOT NULL");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    aliases.put(UUID.fromString(resultSet.getString("uuid")), resultSet.getString("player_alias"));
                }
                return aliases;
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to list player aliases", exception);
            }
        });
    }

    public AliasUpdate setAlias(UUID playerUuid, String alias) {
        if (AliasRules.isReserved(alias)) {
            return AliasUpdate.reservedAlias();
        }
        Optional<String> canonicalAlias = AliasRules.canonicalize(alias);
        if (canonicalAlias.isEmpty()) {
            return AliasUpdate.invalidAlias();
        }
        String canonical = canonicalAlias.get();
        return database.withConnection(connection -> {
            boolean autoCommit;
            try {
                autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                Optional<UUID> aliasOwner = findAliasOwner(connection, canonical);
                if (aliasOwner.isPresent() && !aliasOwner.get().equals(playerUuid)) {
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                    return AliasUpdate.conflict();
                }
                if (findUsernameOwner(connection, canonical).stream().anyMatch(uuid -> !uuid.equals(playerUuid))) {
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                    return AliasUpdate.usernameConflict();
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET player_alias = ?, updated_at = ? WHERE uuid = ?")) {
                    statement.setString(1, canonical);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, playerUuid.toString());
                    statement.executeUpdate();
                }
                connection.commit();
                connection.setAutoCommit(autoCommit);
                return AliasUpdate.updated(playerUuid, canonical);
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw new IllegalStateException("Unable to save player alias", exception);
            }
        });
    }

    private Optional<UUID> findAliasOwner(java.sql.Connection connection, String alias) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid FROM players WHERE player_alias = ? COLLATE NOCASE")) {
            statement.setString(1, alias);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(UUID.fromString(resultSet.getString(1))) : Optional.empty();
            }
        }
    }

    private Set<UUID> findUsernameOwner(java.sql.Connection connection, String username) throws SQLException {
        Set<UUID> owners = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid FROM players WHERE lower(username) = lower(?)")) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    owners.add(UUID.fromString(resultSet.getString(1)));
                }
            }
        }
        return owners;
    }

    private Optional<Player> find(java.sql.Connection connection, String sql, String value) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(player(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to find player", exception);
        }
    }

    private Player player(ResultSet resultSet) throws SQLException {
        return new Player(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("username"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                resultSet.getString("player_alias"));
    }
}
