package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditHardeningTest {
    @Test
    void actorAndTargetTaxonomyAreValidated() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new Actor(ActorType.PLAYER, null, "Ada"));
        assertThrows(IllegalArgumentException.class, () -> new Actor(ActorType.CONSOLE, player, "Console"));
        assertThrows(IllegalArgumentException.class, () -> new Actor(ActorType.SYSTEM, player, null));
        assertThrows(IllegalArgumentException.class, () -> new Actor(ActorType.UNKNOWN, player, null));
        assertThrows(IllegalArgumentException.class, () -> new Target("Block Target", "x"));
        assertThrows(IllegalArgumentException.class, () -> new Target("BLOCK", "x"));
        assertThrows(IllegalArgumentException.class, () -> new Target("block", ""));
        assertThrows(IllegalArgumentException.class, () -> new Target("block", "bad value"));
        assertThrows(IllegalArgumentException.class, () -> new Target("block", "bad\nvalue"));
        assertThrows(IllegalArgumentException.class, () -> new Target("block", "bad😀"));
        assertEquals(new Target("block", "a" + "x".repeat(255)).id().length(), 256);
        assertThrows(IllegalArgumentException.class, () -> new Target("block", "a" + "x".repeat(256)));
    }

    @Test
    void payloadPolicyRejectsSensitiveVariantsAndValues() {
        for (String key : List.of("chat_message", "client_ip", "stackTrace", "rawCommand", "commandLine",
                "nonce", "salt", "hash", "password")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AuditPayload.forAction("lock.created", AuditPayload.code(key, "safe")));
        }
        for (String value : List.of("password", "chat message", "protocol-token", "sha256-hash")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AuditPayload.forAction("lock.created", AuditPayload.code("item_type", value)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("lock.created", AuditPayload.playerName("chat message")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("future.unknown", AuditPayload.itemType("chest")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.fromJson("lock.created", "{\"kind\":\"chest\"}"));
        assertEquals("{\"kind\":\"chest\"}",
                AuditPayload.fromStoredJson("lock.created", "{\"kind\":\"chest\"}").json());
        assertEquals("not-json", AuditPayload.fromStoredJson("lock.created", "not-json").json());
    }

    @Test
    void payloadHandlesJsonUnicodeAndMalformedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("whitelist.updated", AuditPayload.playerName("bad\uD800")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.fromJson("whitelist.updated", "{\"player_name\":\"bad\\uD800\"}"));
        AuditPayload payload = AuditPayload.forAction("whitelist.updated", AuditPayload.operation("updated"),
                AuditPayload.playerName("quote \" slash \\ café 😀"));
        assertEquals(payload, AuditPayload.fromJson("whitelist.updated", payload.json()));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.fromJson("whitelist.updated", "{\"player_name\":\"x\""));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.fromJson("whitelist.updated", "{\"operation\":\"x\",\"operation\":\"y\"}"));
    }

    @Test
    void payloadAcceptsTheUtf8LimitButRejectsOneByteOver() {
        String oversized = "{\"player_name\":\"" + "x".repeat(AuditPayload.MAX_JSON_BYTES) + "\"}";
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.fromJson("whitelist.updated", oversized));
        assertEquals("{}", AuditPayload.empty().json());
    }

    @Test
    void callerOwnedTransactionCommitsBusinessAndAuditTogether(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("caller-commit.db"));
        AuditRepository repository = new AuditRepository(database);
        AuditEvent event = event("system.first");
        database.withConnection(connection -> {
            try {
                connection.setAutoCommit(false);
                connection.createStatement().execute("CREATE TABLE business_state (value TEXT NOT NULL)");
                connection.createStatement().execute("INSERT INTO business_state(value) VALUES ('committed')");
                repository.appendBatch(connection, List.of(event));
                connection.commit();
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return null;
        });
        assertEquals(1, repository.findAll().size());
        assertEquals(1, count(database, "business_state"));
    }

    @Test
    void callerOwnedRollbackRemovesBusinessAndAuditRowsTogether(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("caller-rollback.db"));
        AuditRepository repository = new AuditRepository(database);
        AuditEvent first = event("system.first");
        AuditEvent duplicate = event("system.second");
        repository.append(duplicate);
        database.withConnection(connection -> {
            try {
                connection.createStatement().execute("CREATE TABLE business_state (value TEXT NOT NULL)");
                connection.setAutoCommit(false);
                connection.createStatement().execute("INSERT INTO business_state(value) VALUES ('rolled-back')");
                assertThrows(IllegalStateException.class,
                        () -> repository.appendBatch(connection, List.of(first, duplicate)));
                connection.rollback();
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return null;
        });
        assertEquals(1, repository.findAll().size());
        assertEquals(0, count(database, "business_state"));
        assertFalse(repository.findByEventId(first.eventId()).isPresent());
    }

    @Test
    void callerOwnedAppendRejectsAutoCommit(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("auto-commit.db"));
        AuditRepository repository = new AuditRepository(database);
        assertThrows(IllegalArgumentException.class, () -> database.withConnection(connection -> {
            repository.appendBatch(connection, List.of(event("system.started")));
            return null;
        }));
    }

    @Test
    void validCallerOwnedAppendDoesNotCommitOrRollback(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("transaction-calls.db"));
        AuditRepository repository = new AuditRepository(database);
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        database.withConnection(connection -> {
            try {
                connection.setAutoCommit(false);
                Connection counting = (Connection) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("commit")) {
                                commits.incrementAndGet();
                            } else if (method.getName().equals("rollback")) {
                                rollbacks.incrementAndGet();
                            }
                            try {
                                return method.invoke(connection, args);
                            } catch (java.lang.reflect.InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
                repository.appendBatch(counting, List.of(event("system.started")));
                counting.commit();
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return null;
        });
        assertEquals(1, commits.get());
        assertEquals(0, rollbacks.get());
    }

    @Test
    void migrationCanBeRunAgainAndActorSchemaRejectsInvalidRows(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("schema.db"));
        database.withConnection(connection -> {
                new MigrationRunner(new MigrationLoader().load()).run(connection);
                assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "INSERT INTO audit_events(event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, "
                            + "source, action, outcome, actor_type, payload_version, payload_json) VALUES "
                            + "('e','r','c',1,1,'system','system.started','success','player',1,'{}')"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "INSERT INTO audit_events(event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, "
                            + "source, action, outcome, actor_uuid, actor_type, payload_version, payload_json) VALUES "
                            + "('e2','r','c',1,1,'system','system.started','success','not-a-uuid','system',1,'{}')"));
            return null;
        });
    }

    @Test
    void hardeningUpgradePreservesValidLegacyRowsSequenceAndIndexes(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = new SQLiteDatabase(directory.resolve("upgrade-valid.db"));
        MigrationRunner initial = migrationsThrough("20261005090000_create_audit_events");
        MigrationRunner all = new MigrationRunner(new MigrationLoader().load());
        database.withConnection(connection -> {
            initial.run(connection);
            insertLegacyRow(connection, "00000000-0000-0000-0000-000000000004",
                    "00000000-0000-0000-0000-000000000001", "block", 42, "{\"kind\":\"chest\"}");
            return null;
        });

        database.withConnection(connection -> {
            all.run(connection);
            try (var result = connection.createStatement().executeQuery(
                    "SELECT sequence, event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, source, "
                            + "action, outcome, actor_uuid, actor_type, actor_display_name, target_type, target_id, "
                            + "reason_code, payload_version, payload_json FROM audit_events")) {
                assertTrue(result.next());
                assertEquals(42, result.getInt("sequence"));
                assertEquals("00000000-0000-0000-0000-000000000004", result.getString("event_id"));
                assertEquals("00000000-0000-0000-0000-000000000002", result.getString("runtime_id"));
                assertEquals("00000000-0000-0000-0000-000000000003", result.getString("correlation_id"));
                assertEquals(100, result.getLong("occurred_at_ms"));
                assertEquals(101, result.getLong("recorded_at_ms"));
                assertEquals("system", result.getString("source"));
                assertEquals("lock.created", result.getString("action"));
                assertEquals("success", result.getString("outcome"));
                assertEquals("00000000-0000-0000-0000-000000000001", result.getString("actor_uuid"));
                assertEquals("player", result.getString("actor_type"));
                assertEquals("Ada", result.getString("actor_display_name"));
                assertEquals("block", result.getString("target_type"));
                assertEquals("target-1", result.getString("target_id"));
                assertNull(result.getString("reason_code"));
                assertEquals(1, result.getInt("payload_version"));
                assertEquals("{\"kind\":\"chest\"}", result.getString("payload_json"));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            assertActorIndex(connection);
            return null;
        });
        AuditEvent restored = new AuditRepository(database).findAll().getFirst();
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000004"), restored.eventId());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000002"), restored.runtimeId());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000003"), restored.correlationId());
        assertEquals("{\"kind\":\"chest\"}", restored.payload().json());
    }

    @Test
    void invalidLegacyUpgradeRollsBackAndLeavesLegacySchemaAndMigrationUnrecorded(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = new SQLiteDatabase(directory.resolve("upgrade-invalid.db"));
        MigrationRunner initial = migrationsThrough("20261005090000_create_audit_events");
        MigrationRunner all = new MigrationRunner(new MigrationLoader().load());
        database.withConnection(connection -> {
            initial.run(connection);
            insertLegacyRow(connection, "invalid-event", "00000000-0000-0000-0000-000000000001", "block", 43, "{}");
            return null;
        });

        assertThrows(IllegalStateException.class, () -> database.withConnection(connection -> {
            all.run(connection);
            return null;
        }));

        database.withConnection(connection -> {
            try (var table = connection.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'audit_events'")) {
                assertTrue(table.next());
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            assertEquals(1, count(connection, "audit_events"));
            try (var row = connection.createStatement().executeQuery(
                    "SELECT event_id, actor_uuid FROM audit_events")) {
                assertTrue(row.next());
                assertEquals("invalid-event", row.getString("event_id"));
                assertEquals("00000000-0000-0000-0000-000000000001", row.getString("actor_uuid"));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            assertEquals(0, countWhere(connection, "_umbrellaz_migrations", "migration_id = '20261005090100_harden_audit_events'"));
            assertTrue(indexExists(connection, "idx_audit_events_actor_uuid"));
            assertTrue(indexExists(connection, "idx_audit_events_occurred_at_ms"));
            assertTrue(indexExists(connection, "idx_audit_events_action_sequence"));
            assertTrue(indexExists(connection, "idx_audit_events_target"));
            return null;
        });
    }

    @Test
    void hardenedSqlRejectsTargetIdentifiersJavaCannotMap(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("target-sql.db"));
        database.withConnection(connection -> {
            assertSqlTargetRejected(connection, "Block", "target-1", 10);
            assertSqlTargetRejected(connection, "block", "", 11);
            assertSqlTargetRejected(connection, "block", "bad value", 12);
            assertSqlTargetRejected(connection, "block", "bad\nvalue", 13);
            assertSqlTargetRejected(connection, "block", "bad😀", 14);
            assertSqlTargetRejected(connection, "block", "a" + "x".repeat(256), 15);
            return null;
        });
    }

    @Test
    void retentionCutoffIsExclusive(@TempDir java.nio.file.Path directory) {
        AuditRepository repository = new AuditRepository(migratedDatabase(directory.resolve("retention-cutoff.db")));
        repository.appendBatch(List.of(eventAt("old.event", 249), eventAt("cutoff.event", 250)));
        assertEquals(1, repository.deleteOccurredBefore(250, 10));
        assertEquals("cutoff.event", repository.findAll().getFirst().action());
    }

    private AuditEvent event(String action) {
        return eventAt(action, 1);
    }

    private AuditEvent eventAt(String action, long occurredAtMs) {
        return new AuditEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), occurredAtMs, occurredAtMs + 1,
                Source.SYSTEM, action, Outcome.SUCCESS, new Actor(ActorType.SYSTEM, null, null), null, null, 1,
                AuditPayload.empty());
    }

    private SQLiteDatabase migratedDatabase(java.nio.file.Path path) {
        SQLiteDatabase database = new SQLiteDatabase(path);
        MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
        database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        });
        return database;
    }

    private MigrationRunner migrationsThrough(String migrationId) {
        return new MigrationRunner(new MigrationLoader().load().stream()
                .filter(migration -> migration.migrationId().compareTo(migrationId) <= 0)
                .toList());
    }

    private void insertLegacyRow(Connection connection, String eventId, String actorUuid, String targetType,
            int sequence, String payloadJson) {
        try (var statement = connection.prepareStatement(
                "INSERT INTO audit_events(sequence, event_id, runtime_id, correlation_id, occurred_at_ms, "
                        + "recorded_at_ms, source, action, outcome, actor_uuid, actor_type, actor_display_name, "
                        + "target_type, target_id, reason_code, payload_version, payload_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, sequence);
            statement.setString(2, eventId);
            statement.setString(3, "00000000-0000-0000-0000-000000000002");
            statement.setString(4, "00000000-0000-0000-0000-000000000003");
            statement.setLong(5, 100);
            statement.setLong(6, 101);
            statement.setString(7, "system");
            statement.setString(8, "lock.created");
            statement.setString(9, "success");
            statement.setString(10, actorUuid);
            statement.setString(11, "player");
            statement.setString(12, "Ada");
            statement.setString(13, targetType);
            statement.setString(14, "target-1");
            statement.setString(15, null);
            statement.setInt(16, 1);
            statement.setString(17, payloadJson);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertSqlTargetRejected(Connection connection, String targetType, String targetId, int sequence) {
        assertThrows(SQLException.class, () -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO audit_events(event_id, runtime_id, correlation_id, occurred_at_ms, recorded_at_ms, "
                            + "source, action, outcome, actor_type, target_type, target_id, payload_version, payload_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, String.format("00000000-0000-0000-0000-%012d", sequence));
                statement.setString(2, "00000000-0000-0000-0000-000000000002");
                statement.setString(3, "00000000-0000-0000-0000-000000000003");
                statement.setLong(4, 1);
                statement.setLong(5, 2);
                statement.setString(6, "system");
                statement.setString(7, "system.started");
                statement.setString(8, "success");
                statement.setString(9, "system");
                statement.setString(10, targetType);
                statement.setString(11, targetId);
                statement.setInt(12, 1);
                statement.setString(13, "{}");
                statement.executeUpdate();
            }
        });
    }

    private void assertActorIndex(Connection connection) {
        try (var result = connection.createStatement().executeQuery(
                "PRAGMA index_xinfo('idx_audit_events_actor_uuid')")) {
            assertTrue(result.next());
            assertEquals("actor_uuid", result.getString("name"));
            assertEquals(0, result.getInt("desc"));
            assertTrue(result.next());
            assertEquals("sequence", result.getString("name"));
            assertEquals(1, result.getInt("desc"));
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
        assertTrue(indexExists(connection, "idx_audit_events_actor_uuid"));
        try (var result = connection.createStatement().executeQuery("PRAGMA index_list('audit_events')")) {
            while (result.next()) {
                if (result.getString("name").equals("idx_audit_events_actor_uuid")) {
                    assertEquals(1, result.getInt("partial"));
                    return;
                }
            }
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
        throw new AssertionError("Actor index was not listed");
    }

    private boolean indexExists(Connection connection, String name) {
        try (var result = connection.createStatement().executeQuery("PRAGMA index_list('audit_events')")) {
            while (result.next()) {
                if (result.getString("name").equals(name)) {
                    return true;
                }
            }
            return false;
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private int count(Connection connection, String table) {
        return countWhere(connection, table, "1 = 1");
    }

    private int countWhere(Connection connection, String table, String predicate) {
        try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private int count(SQLiteDatabase database, String table) {
        return database.withConnection(connection -> {
            try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                return result.getInt(1);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        });
    }
}
