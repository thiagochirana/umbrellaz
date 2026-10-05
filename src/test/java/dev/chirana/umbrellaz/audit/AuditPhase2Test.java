package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPhase2Test {
    @Test
    void writerReservesRequiredCapacityAndDrainsInBatches(@TempDir java.nio.file.Path directory) throws Exception {
        SQLiteDatabase database = migratedDatabase(directory.resolve("writer.db"));
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditWriter writer = new AuditWriter(executor, new AuditRepository(database), 4, 2);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            writer.submit(event("system.started"), AuditDelivery.BEST_EFFORT);
            writer.submit(event("system.started"), AuditDelivery.BEST_EFFORT);
            assertEquals(AuditWriteResult.DROPPED, writer.submit(event("system.started"), AuditDelivery.BEST_EFFORT).join());
            writer.submit(event("system.started"), AuditDelivery.REQUIRED);
            writer.submit(event("system.started"), AuditDelivery.REQUIRED);
            assertTrue(writer.submit(event("system.started"), AuditDelivery.REQUIRED).isCompletedExceptionally());
            assertEquals(4, writer.status().queued());

            release.countDown();
            writer.flush().join();
            assertEquals(4, new AuditRepository(database).findAll().size());
            assertEquals(1, writer.status().dropped());
            writer.shutdown().join();
            assertTrue(writer.status().closed());
        }
    }

    @Test
    void querySupportsFiltersCursorAndHardPageCap(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("query.db"));
        AuditRepository repository = new AuditRepository(database);
        UUID actor = UUID.randomUUID();
        repository.appendBatch(List.of(
                event(100, "system.started", new Actor(ActorType.PLAYER, actor, "Ada")),
                event(200, "system.started", new Actor(ActorType.PLAYER, actor, "Ada")),
                event(300, "lock.created", new Actor(ActorType.SYSTEM, null, null))));

        AuditPage first = repository.query(new AuditQuery(2, null, "system.started", actor, 150L));
        assertEquals(1, first.events().size());
        assertEquals(200, first.events().getFirst().event().occurredAtMs());
        assertEquals(null, first.nextCursor());

        AuditPage paged = repository.query(new AuditQuery(2, null, null, null, null));
        assertEquals(2, paged.events().size());
        assertEquals(300, paged.events().getFirst().event().occurredAtMs());
        assertTrue(paged.nextCursor() != null);
        AuditPage next = repository.query(new AuditQuery(2, paged.nextCursor(), null, null, null));
        assertEquals(1, next.events().size());
        assertEquals(100, next.events().getFirst().event().occurredAtMs());
    }

    @Test
    void flushUsesAdmissionWatermarkAndWriterYieldsAtBatchBoundary(@TempDir java.nio.file.Path directory)
            throws Exception {
        SQLiteDatabase database = migratedDatabase(directory.resolve("watermark.db"));
        AuditRepository repository = new AuditRepository(database);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditWriter writer = new AuditWriter(executor, repository);
            CountDownLatch gateStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                gateStarted.countDown();
                await(release);
                return null;
            });
            assertTrue(gateStarted.await(2, TimeUnit.SECONDS));

            for (int index = 0; index < AuditWriter.MAX_BATCH_SIZE; index++) {
                writer.submit(event("system.started"), AuditDelivery.REQUIRED);
            }
            var firstFlush = writer.flush();
            writer.submit(event("system.started"), AuditDelivery.REQUIRED);
            CountDownLatch sentinelStarted = new CountDownLatch(1);
            CountDownLatch sentinelRelease = new CountDownLatch(1);
            AtomicInteger sentinelCount = new AtomicInteger();
            executor.submit(() -> {
                sentinelCount.set(repository.findAll().size());
                sentinelStarted.countDown();
                await(sentinelRelease);
                return null;
            });
            release.countDown();

            assertTrue(sentinelStarted.await(2, TimeUnit.SECONDS));
            assertTrue(firstFlush.isDone());
            firstFlush.join();
            assertEquals(AuditWriter.MAX_BATCH_SIZE, sentinelCount.get());
            assertEquals(AuditWriter.MAX_BATCH_SIZE, repository.findAll().size());
            sentinelRelease.countDown();
            writer.flush().join();
            assertEquals(AuditWriter.MAX_BATCH_SIZE + 1, repository.findAll().size());
            writer.shutdown().join();
        }
    }

    @Test
    void shutdownDrainsAcceptedEventsInFifoOrder(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("shutdown.db"));
        AuditRepository repository = new AuditRepository(database);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditWriter writer = new AuditWriter(executor, repository);
            List<AuditEvent> expected = new ArrayList<>();
            for (int index = 0; index < AuditWriter.MAX_BATCH_SIZE + 1; index++) {
                AuditEvent event = event("system.started");
                expected.add(event);
                writer.submit(event, AuditDelivery.REQUIRED);
            }
            writer.shutdown().join();
            List<AuditStoredEvent> stored = repository.findAllStored();
            assertEquals(AuditWriter.MAX_BATCH_SIZE + 1, stored.size());
            for (int index = 0; index < expected.size(); index++) {
                assertEquals(expected.get(index).eventId(), stored.get(index).event().eventId());
            }
        }
    }

    @Test
    void persistenceFailureMakesWriterUnhealthyWithoutRetrying(@TempDir java.nio.file.Path directory) throws Exception {
        SQLiteDatabase database = migratedDatabase(directory.resolve("failure.db"));
        AuditRepository repository = new AuditRepository(database);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditWriter writer = new AuditWriter(executor, repository);
            CountDownLatch gateStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                gateStarted.countDown();
                await(release);
                return null;
            });
            try {
                assertTrue(gateStarted.await(2, TimeUnit.SECONDS));
                var pending = writer.submit(event("system.started"), AuditDelivery.REQUIRED);
                database.withConnection(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("DROP TABLE audit_events");
                        return null;
                    } catch (SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                release.countDown();
                assertTrue(pending.isCompletedExceptionally() || pending.handle((value, failure) -> failure != null).join());
                assertFalse(writer.status().healthy());
                assertTrue(writer.submit(event("system.started"), AuditDelivery.REQUIRED).isCompletedExceptionally());
                assertTrue(writer.flush().isCompletedExceptionally());
                assertEquals(0, writer.status().queued());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void serviceConstructsTrustedIdentityAndTimestamps(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("service.db"));
        UUID runtimeId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(123_456), ZoneOffset.UTC);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditRepository repository = new AuditRepository(database);
            AuditWriter writer = new AuditWriter(executor, repository);
            AuditService service = new AuditService(runtimeId, executor, repository, writer, clock);
            AuditRecordRequest request = new AuditRecordRequest(
                    new AuditRecordContext(new Actor(ActorType.SYSTEM, null, null), correlationId),
                    Source.SYSTEM, "system.started", Outcome.SUCCESS, null, null, 1, AuditPayload.empty(),
                    AuditDelivery.REQUIRED);
            assertEquals(AuditWriteResult.DURABLE, service.record(request).join());
            AuditEvent stored = repository.findAll().getFirst();
            assertEquals(runtimeId, stored.runtimeId());
            assertEquals(correlationId, stored.correlationId());
            assertEquals(123_456, stored.occurredAtMs());
            assertEquals(123_456, stored.recordedAtMs());
            assertEquals(1, service.query(AuditQuery.defaults()).join().events().size());
            service.shutdown().join();
        }
    }

    @Test
    void serviceQueryFlushesAnAcceptedRecordWithoutJoiningItsFutureFirst(@TempDir java.nio.file.Path directory)
            throws Exception {
        SQLiteDatabase database = migratedDatabase(directory.resolve("service-watermark.db"));
        AuditRepository repository = new AuditRepository(database);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditWriter writer = new AuditWriter(executor, repository);
            AuditService service = new AuditService(UUID.randomUUID(), executor, repository, writer);
            CountDownLatch gateStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                gateStarted.countDown();
                await(release);
                return null;
            });
            assertTrue(gateStarted.await(2, TimeUnit.SECONDS));

            CompletableFuture<AuditWriteResult> record = service.record(requiredRequest());
            assertFalse(record.isDone());
            CompletableFuture<AuditPage> query = service.query(AuditQuery.defaults());
            assertFalse(query.isDone());
            release.countDown();

            AuditPage page = query.join();
            assertEquals(1, page.events().size());
            assertEquals("system.started", page.events().getFirst().event().action());
            assertEquals(AuditWriteResult.DURABLE, record.join());
            service.shutdown().join();
        }
    }

    @Test
    void transactionalAuditSharesBusinessCommitAndRollback(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("transaction.db"));
        AuditRepository repository = new AuditRepository(database);
        UUID runtimeId = UUID.randomUUID();
        UUID committedCorrelationId = UUID.randomUUID();
        UUID rolledBackCorrelationId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(456_789), ZoneOffset.UTC);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditWriter writer = new AuditWriter(executor, repository);
            AuditService service = new AuditService(runtimeId, executor, repository, writer, clock);
            executor.submit(() -> database.withConnection(connection -> {
                try {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("CREATE TABLE business_state(value TEXT NOT NULL)");
                        statement.executeUpdate("INSERT INTO business_state(value) VALUES ('committed')");
                    }
                    service.recordInTransaction(connection, requiredRequest(committedCorrelationId));
                    connection.commit();
                    return null;
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            })).join();
            assertEquals(1, repository.findAll().size());

            executor.submit(() -> database.withConnection(connection -> {
                try {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("INSERT INTO business_state(value) VALUES ('rolled-back')");
                    }
                    service.recordInTransaction(connection, requiredRequest(rolledBackCorrelationId));
                    connection.rollback();
                    return null;
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            })).join();
            assertEquals(1, repository.findAll().size());
            AuditEvent committed = repository.findAll().getFirst();
            assertEquals(runtimeId, committed.runtimeId());
            assertEquals(committedCorrelationId, committed.correlationId());
            assertEquals(456_789, committed.occurredAtMs());
            assertEquals(456_789, committed.recordedAtMs());
            int businessRows = database.withConnection(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM business_state")) {
                    return result.next() ? result.getInt(1) : 0;
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertEquals(1, businessRows);
            service.shutdown().join();
        }
    }

    @Test
    void retentionRunsInBoundedDailyScheduledPasses(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("retention-service.db"));
        long now = 10_000_000_000L;
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            AuditRepository repository = new AuditRepository(database);
            AuditWriter writer = new AuditWriter(executor, repository);
            AuditService service = new AuditService(UUID.randomUUID(), executor, repository, writer,
                    Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
            List<AuditEvent> events = new ArrayList<>();
            for (int index = 0; index < AuditService.RETENTION_BATCH_SIZE + 1; index++) {
                events.add(event(now - AuditService.RETENTION_PERIOD.toMillis() - 1, "system.started",
                        new Actor(ActorType.SYSTEM, null, null)));
            }
            events.add(event(now - AuditService.RETENTION_PERIOD.toMillis(), "system.started",
                    new Actor(ActorType.SYSTEM, null, null)));
            repository.appendBatch(events);
            assertEquals(AuditService.RETENTION_BATCH_SIZE + 1, service.runRetentionIfDue(now).join());
            assertEquals(1, repository.findAll().size());
            assertEquals(0, service.runRetentionIfDue(now + 1_000).join());
            service.shutdown().join();
        }
    }

    private AuditEvent event(long occurredAt, String action, Actor actor) {
        AuditPayload payload = action.equals("lock.created")
                ? AuditPayload.forAction(action, AuditPayload.itemType("chest"), AuditPayload.count(1))
                : AuditPayload.empty();
        return new AuditEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), occurredAt, occurredAt + 1,
                Source.SYSTEM, action, Outcome.SUCCESS, actor, null, null, 1, payload);
    }

    private AuditEvent event(String action) {
        return event(1, action, new Actor(ActorType.SYSTEM, null, null));
    }

    private AuditRecordRequest requiredRequest() {
        return requiredRequest(UUID.randomUUID());
    }

    private AuditRecordRequest requiredRequest(UUID correlationId) {
        return new AuditRecordRequest(
                new AuditRecordContext(new Actor(ActorType.SYSTEM, null, null), correlationId), Source.SYSTEM,
                "system.started", Outcome.SUCCESS, null, null, 1, AuditPayload.empty(), AuditDelivery.REQUIRED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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
}
