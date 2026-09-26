package dev.chirana.umbrellaz.infra.db.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Function;

public final class SQLiteDatabase {
    private final Path databasePath;

    public SQLiteDatabase(Path databasePath) {
        this.databasePath = databasePath;
    }

    public <T> T withConnection(Function<Connection, T> operation) {
        try {
            Files.createDirectories(databasePath.getParent());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
                configure(connection);
                return operation.apply(connection);
            }
        } catch (SQLException | java.io.IOException exception) {
            throw new IllegalStateException("SQLite operation failed", exception);
        }
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }
}
