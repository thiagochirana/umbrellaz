package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRepositoryTest {
    @Test
    void migrationCreatesAuditTableAndIndexes(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("migration.db"));
        database.withConnection(connection -> {
            try (var columns = connection.createStatement().executeQuery("PRAGMA table_info(audit_events)")) {
                Set<String> names = new java.util.HashSet<>();
                while (columns.next()) {
                    names.add(columns.getString("name"));
                }
                assertTrue(names.containsAll(Set.of("sequence", "event_id", "runtime_id", "correlation_id",
                        "occurred_at_ms", "recorded_at_ms", "source", "action", "outcome", "actor_uuid",
                        "target_type", "target_id", "reason_code", "payload_version", "payload_json")));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            try (var indexes = connection.createStatement().executeQuery("PRAGMA index_list(audit_events)")) {
                Set<String> names = new java.util.HashSet<>();
                while (indexes.next()) {
                    names.add(indexes.getString("name"));
                }
                assertTrue(names.containsAll(Set.of("idx_audit_events_occurred_at_ms",
                        "idx_audit_events_action_sequence", "idx_audit_events_actor_uuid",
                        "idx_audit_events_target")));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            return null;
        });
    }

    @Test
    void appendsBatchAndMapsAllFields(@TempDir java.nio.file.Path directory) {
        AuditRepository repository = new AuditRepository(migratedDatabase(directory.resolve("append.db")));
        UUID player = UUID.randomUUID();
        AuditEvent first = event(100, new Actor(ActorType.PLAYER, player, "Ada"),
                new Target("block", "overworld:1,2,3"), "lock.created",
                AuditPayload.forAction("lock.created", AuditPayload.itemType("chest"), AuditPayload.count(1)));
        AuditEvent second = event(200, new Actor(ActorType.CONSOLE, null, "Console"), null,
                "whitelist.updated", AuditPayload.empty());

        repository.appendBatch(List.of(first, second));

        assertEquals(List.of(first, second), repository.findAll());
        assertEquals(first, repository.findByEventId(first.eventId()).orElseThrow());
    }

    @Test
    void enforcesUniquenessValidationAndPayloadBounds(@TempDir java.nio.file.Path directory) {
        AuditRepository repository = new AuditRepository(migratedDatabase(directory.resolve("validation.db")));
        AuditEvent event = event(100, new Actor(ActorType.SYSTEM, null, null), null,
                "system.started", AuditPayload.empty());
        repository.append(event);
        assertThrows(IllegalStateException.class, () -> repository.append(event));
        assertEquals(1, repository.findAll().size());

        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(null, UUID.randomUUID(),
                UUID.randomUUID(), 1, 1, Source.SYSTEM, "system.started", Outcome.SUCCESS,
                new Actor(ActorType.SYSTEM, null, null), null, null, 1, AuditPayload.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("lock.created", AuditPayload.code("item_type", "password")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("lock.created", AuditPayload.code("item_type", "x".repeat(1025))));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("lock.created", AuditPayload.code("item_type", "x".repeat(1025))));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 1, Source.SYSTEM, "Not Stable", Outcome.SUCCESS,
                new Actor(ActorType.SYSTEM, null, null), null, null, 1, AuditPayload.empty()));
    }

    @Test
    void deletesOldEventsInRequestedChunks(@TempDir java.nio.file.Path directory) {
        AuditRepository repository = new AuditRepository(migratedDatabase(directory.resolve("retention.db")));
        repository.appendBatch(List.of(
                event(100, new Actor(ActorType.SYSTEM, null, null), null, "old.one", AuditPayload.empty()),
                event(200, new Actor(ActorType.SYSTEM, null, null), null, "old.two", AuditPayload.empty()),
                event(300, new Actor(ActorType.SYSTEM, null, null), null, "new.one", AuditPayload.empty())));

        assertEquals(1, repository.deleteOccurredBefore(250, 1));
        assertEquals(2, repository.findAll().size());
        assertEquals(1, repository.deleteBefore(250, 10));
        assertEquals(1, repository.findAll().size());
        assertEquals("new.one", repository.findAll().getFirst().action());
    }

    @Test
    void duplicateInBatchRollsBackEarlierRows(@TempDir java.nio.file.Path directory) {
        AuditRepository repository = new AuditRepository(migratedDatabase(directory.resolve("rollback.db")));
        AuditEvent first = event(100, new Actor(ActorType.SYSTEM, null, null), null,
                "system.first", AuditPayload.empty());
        AuditEvent duplicate = event(200, new Actor(ActorType.SYSTEM, null, null), null,
                "system.second", AuditPayload.empty());
        repository.append(duplicate);

        assertThrows(IllegalStateException.class, () -> repository.appendBatch(List.of(first, duplicate)));
        assertEquals(List.of(duplicate), repository.findAll());
        assertFalse(repository.findByEventId(first.eventId()).isPresent());
    }

    private AuditEvent event(long occurredAtMs, Actor actor, Target target, String action, AuditPayload payload) {
        return new AuditEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), occurredAtMs,
                occurredAtMs + 1, Source.SYSTEM, action, Outcome.SUCCESS, actor, target, "ok", 1, payload);
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
