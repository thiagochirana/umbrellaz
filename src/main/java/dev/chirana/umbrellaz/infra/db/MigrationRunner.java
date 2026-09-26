package dev.chirana.umbrellaz.infra.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MigrationRunner {
    private static final String MIGRATIONS_TABLE = "_umbrellaz_migrations";
    private final List<Migration> migrations;

    public MigrationRunner(List<Migration> migrations) {
        this.migrations = migrations.stream()
                .sorted(Comparator.comparing(Migration::migrationId))
                .toList();
        validateMigrationIds(this.migrations);
    }

    public void run(Connection connection) {
        try {
            if (!migrationTableExists(connection)) {
                if (migrations.isEmpty()) {
                    throw new IllegalStateException("Migration controller table is missing and no migrations are available");
                }
                execute(connection, migrations.getFirst());
            }
            for (Migration migration : migrations) {
                if (!isApplied(connection, migration.migrationId())) {
                    execute(connection, migration);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to inspect migration controller table", exception);
        }
    }

    private void execute(Connection connection, Migration migration) {
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(migration.sql());
            }
            if (!migrationTableExists(connection)) {
                throw new IllegalStateException("Migration " + migration.migrationId()
                        + " did not create the " + MIGRATIONS_TABLE + " controller table");
            }
            try (PreparedStatement record = connection.prepareStatement(
                    "INSERT INTO " + MIGRATIONS_TABLE + "(migration_id, created_at, executed_at) VALUES (?, ?, ?)")) {
                record.setString(1, migration.migrationId());
                record.setString(2, migration.createdAt().toString());
                record.setString(3, Instant.now().toString());
                record.executeUpdate();
            }
            connection.commit();
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw new IllegalStateException("Migration failed: " + migration.migrationId(), exception);
        }
    }

    private boolean migrationTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, MIGRATIONS_TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean isApplied(Connection connection, String migrationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + MIGRATIONS_TABLE + " WHERE migration_id = ?")) {
            statement.setString(1, migrationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void validateMigrationIds(List<Migration> migrations) {
        Set<String> ids = new HashSet<>();
        for (Migration migration : migrations) {
            if (!ids.add(migration.migrationId())) {
                throw new IllegalArgumentException("Duplicate migration id: " + migration.migrationId());
            }
        }
    }
}
