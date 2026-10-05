package dev.chirana.umbrellaz.runtime;

import dev.chirana.umbrellaz.audit.AuditQuery;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRepository;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.AuditWriter;
import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditShutdownCoordinatorTest {
    @Test
    void shutdownDrainsAuditBeforeClosingExecutor(@TempDir java.nio.file.Path directory) throws Exception {
        SQLiteDatabase database = migratedDatabase(directory.resolve("coordinated-shutdown.db"));
        AuditRepository repository = new AuditRepository(database);
        DatabaseExecutor executor = new DatabaseExecutor();
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

        service.record(requiredRequest());
        CompletableFuture<Void> shutdown = new AuditShutdownCoordinator(service, executor).shutdown();
        assertFalse(shutdown.isDone());

        release.countDown();
        shutdown.join();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, repository.query(AuditQuery.defaults()).events().size());
        assertTrue(executor.submit(() -> null).isCompletedExceptionally());
    }

    private AuditRecordRequest requiredRequest() {
        return new AuditRecordRequest(
                new AuditRecordContext(new Actor(ActorType.SYSTEM, null, null), UUID.randomUUID()), Source.SYSTEM,
                "system.started", Outcome.SUCCESS, null, null, 1, AuditPayload.empty(), AuditDelivery.REQUIRED);
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
