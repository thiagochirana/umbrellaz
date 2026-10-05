package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AuditService implements AutoCloseable {
    public static final Duration RETENTION_PERIOD = Duration.ofDays(90);
    public static final long RETENTION_INTERVAL_MS = Duration.ofDays(1).toMillis();
    public static final int RETENTION_BATCH_SIZE = 1_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
    private final UUID runtimeId;
    private final DatabaseExecutor databaseExecutor;
    private final AuditRepository repository;
    private final AuditWriter writer;
    private final Clock clock;
    private final Object retentionLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long lastRetentionAtMs = Long.MIN_VALUE;
    private CompletableFuture<Integer> retentionFuture;
    private CompletableFuture<Void> shutdownResult;

    public AuditService(UUID runtimeId, DatabaseExecutor databaseExecutor, AuditRepository repository,
            AuditWriter writer) {
        this(runtimeId, databaseExecutor, repository, writer, Clock.systemUTC());
    }

    public AuditService(UUID runtimeId, DatabaseExecutor databaseExecutor, AuditRepository repository,
            AuditWriter writer, Clock clock) {
        this.runtimeId = Objects.requireNonNull(runtimeId, "Runtime id must not be null");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "Database executor must not be null");
        this.repository = Objects.requireNonNull(repository, "Audit repository must not be null");
        this.writer = Objects.requireNonNull(writer, "Audit writer must not be null");
        this.clock = Objects.requireNonNull(clock, "Audit clock must not be null");
    }

    public CompletableFuture<AuditWriteResult> record(AuditRecordRequest request) {
        Objects.requireNonNull(request, "Audit record request must not be null");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Audit service is closed"));
        }
        AuditEvent event = createEvent(request);
        return writer.submit(event, request.delivery());
    }

    /**
     * Records a REQUIRED audit event in a caller-owned business transaction.
     * The caller must invoke this on the DatabaseExecutor, with auto-commit
     * disabled, and must commit or roll back the transaction itself.
     */
    public AuditEvent recordInTransaction(Connection connection, AuditRecordRequest request) {
        Objects.requireNonNull(connection, "Connection must not be null");
        Objects.requireNonNull(request, "Audit record request must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Audit service is closed");
        }
        if (request.delivery() != AuditDelivery.REQUIRED) {
            throw new IllegalArgumentException("Transactional audit records must be REQUIRED");
        }
        AuditEvent event = createEvent(request);
        repository.append(connection, event);
        return event;
    }

    public CompletableFuture<AuditPage> query(AuditQuery query) {
        return writer.flush().thenCompose(ignored -> databaseExecutor.submit(() -> repository.query(query)));
    }

    public CompletableFuture<AuditStoredEvent> show(UUID eventId) {
        return writer.flush().thenCompose(ignored -> databaseExecutor.submit(() ->
                repository.findStoredByEventId(eventId).orElseThrow(() ->
                        new IllegalArgumentException("Audit event not found"))));
    }

    public AuditWriterStatus status() {
        return writer.status();
    }

    public CompletableFuture<Void> flush() {
        return writer.flush();
    }

    public CompletableFuture<Integer> runRetentionIfDue(long nowMs) {
        synchronized (retentionLock) {
            if (closed.get() || retentionFuture != null && !retentionFuture.isDone()) {
                return retentionFuture == null ? CompletableFuture.completedFuture(0) : retentionFuture;
            }
            if (lastRetentionAtMs != Long.MIN_VALUE && nowMs - lastRetentionAtMs < RETENTION_INTERVAL_MS) {
                return CompletableFuture.completedFuture(0);
            }
            lastRetentionAtMs = nowMs;
            long cutoff = Math.max(0, nowMs - RETENTION_PERIOD.toMillis());
            retentionFuture = purge(cutoff, 0);
            retentionFuture.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.error("Audit retention purge failed", failure);
                }
            });
            return retentionFuture;
        }
    }

    public CompletableFuture<Void> shutdown() {
        synchronized (retentionLock) {
            if (shutdownResult != null) {
                return shutdownResult;
            }
            closed.set(true);
            CompletableFuture<Void> writerShutdown = writer.shutdown();
            shutdownResult = shutdownFuture(retentionFuture)
                    .thenCombine(writerShutdown, (ignored, ignoredWriter) -> null);
            return shutdownResult;
        }
    }

    private CompletableFuture<Integer> purge(long cutoff, int deleted) {
        return databaseExecutor.submit(() -> repository.deleteOccurredBefore(cutoff, RETENTION_BATCH_SIZE))
                .thenCompose(count -> count == RETENTION_BATCH_SIZE
                        ? purge(cutoff, deleted + count)
                        : CompletableFuture.completedFuture(deleted + count));
    }

    private AuditEvent createEvent(AuditRecordRequest request) {
        long now = clock.millis();
        return new AuditEvent(UUID.randomUUID(), runtimeId, request.context().correlationId(), now, now,
                request.source(), request.action(), request.outcome(), request.context().actor(), request.target(),
                request.reasonCode(), request.payloadVersion(), request.payload());
    }

    private CompletableFuture<Void> shutdownFuture(CompletableFuture<Integer> currentRetention) {
        return currentRetention == null
                ? CompletableFuture.completedFuture(null)
                : currentRetention.thenApply(ignored -> null);
    }

    @Override
    public void close() {
        shutdown();
    }
}
