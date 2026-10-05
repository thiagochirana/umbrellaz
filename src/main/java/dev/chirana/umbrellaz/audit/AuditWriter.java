package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

public final class AuditWriter implements AutoCloseable {
    public static final int DEFAULT_CAPACITY = 1_000;
    public static final int DEFAULT_REQUIRED_RESERVE = 100;
    public static final int MAX_BATCH_SIZE = 100;

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditWriter.class);
    private final DatabaseExecutor databaseExecutor;
    private final AuditRepository repository;
    private final int capacity;
    private final int requiredReserve;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final List<FlushWaiter> flushWaiters = new ArrayList<>();
    private final Object lock = new Object();
    private boolean draining;
    private boolean closed;
    private boolean healthy = true;
    private long admittedSequence;
    private long durableSequence;
    private long accepted;
    private long dropped;
    private long failed;
    private long rejected;
    private long lastDropLogNanos;
    private CompletableFuture<Void> shutdownFuture;

    public AuditWriter(DatabaseExecutor databaseExecutor, AuditRepository repository) {
        this(databaseExecutor, repository, DEFAULT_CAPACITY, DEFAULT_REQUIRED_RESERVE);
    }

    public AuditWriter(DatabaseExecutor databaseExecutor, AuditRepository repository, int capacity,
            int requiredReserve) {
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "Database executor must not be null");
        this.repository = Objects.requireNonNull(repository, "Audit repository must not be null");
        if (capacity < 1 || requiredReserve < 0 || requiredReserve >= capacity) {
            throw new IllegalArgumentException("Invalid audit writer capacity or required reserve");
        }
        this.capacity = capacity;
        this.requiredReserve = requiredReserve;
    }

    public CompletableFuture<AuditWriteResult> submit(AuditEvent event, AuditDelivery delivery) {
        Objects.requireNonNull(event, "Audit event must not be null");
        Objects.requireNonNull(delivery, "Audit delivery must not be null");
        Pending pending = new Pending(event);
        synchronized (lock) {
            if (closed || !healthy) {
                rejected++;
                return CompletableFuture.failedFuture(new RejectedExecutionException(
                        "Audit writer is unavailable"));
            }
            int bestEffortLimit = capacity - requiredReserve;
            if (delivery == AuditDelivery.BEST_EFFORT && queue.size() >= bestEffortLimit) {
                dropped++;
                logDropLocked();
                return CompletableFuture.completedFuture(AuditWriteResult.DROPPED);
            }
            if (queue.size() >= capacity) {
                rejected++;
                if (delivery == AuditDelivery.BEST_EFFORT) {
                    dropped++;
                    logDropLocked();
                    return CompletableFuture.completedFuture(AuditWriteResult.DROPPED);
                }
                return CompletableFuture.failedFuture(new RejectedExecutionException(
                        "Audit writer required capacity is full"));
            }
            pending.admissionSequence = ++admittedSequence;
            queue.addLast(pending);
            accepted++;
            scheduleDrainLocked();
            return pending.future;
        }
    }

    public CompletableFuture<Void> flush() {
        synchronized (lock) {
            long watermark = admittedSequence;
            if (!healthy) {
                return CompletableFuture.failedFuture(new IllegalStateException("Audit writer is unhealthy"));
            }
            if (durableSequence >= watermark) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> result = new CompletableFuture<>();
            flushWaiters.add(new FlushWaiter(watermark, result));
            return result;
        }
    }

    public CompletableFuture<Void> shutdown() {
        synchronized (lock) {
            if (shutdownFuture != null) {
                return shutdownFuture;
            }
            closed = true;
            shutdownFuture = new CompletableFuture<>();
            if (!healthy) {
                failQueuedLocked(new IllegalStateException("Audit writer is unhealthy"));
                finishWaitersLocked();
            } else if (queue.isEmpty() && !draining) {
                shutdownFuture.complete(null);
            } else {
                scheduleDrainLocked();
            }
            return shutdownFuture;
        }
    }

    public AuditWriterStatus status() {
        synchronized (lock) {
            return new AuditWriterStatus(accepted, dropped, failed, rejected, queue.size(), healthy, closed);
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void scheduleDrainLocked() {
        if (draining || queue.isEmpty() || !healthy) {
            return;
        }
        draining = true;
        databaseExecutor.submit(this::drain).whenComplete((ignored, failure) -> {
            synchronized (lock) {
                draining = false;
                if (failure != null) {
                    markUnhealthyLocked();
                    failQueuedLocked(failure);
                    failFlushWaitersLocked();
                }
                finishWaitersLocked();
                if (!queue.isEmpty() && healthy) {
                    scheduleDrainLocked();
                }
            }
        });
    }

    private Void drain() {
        List<Pending> batch = new ArrayList<>(MAX_BATCH_SIZE);
        synchronized (lock) {
            while (!queue.isEmpty() && batch.size() < MAX_BATCH_SIZE) {
                batch.add(queue.removeFirst());
            }
        }
        if (batch.isEmpty()) {
            return null;
        }
        try {
            repository.appendBatch(batch.stream().map(Pending::event).toList());
            synchronized (lock) {
                durableSequence = batch.getLast().admissionSequence;
                completeFlushWaitersLocked();
                batch.forEach(pending -> pending.future.complete(AuditWriteResult.DURABLE));
            }
        } catch (Throwable failure) {
            synchronized (lock) {
                markUnhealthyLocked();
                failed += batch.size();
                batch.forEach(pending -> pending.future.completeExceptionally(failure));
                failQueuedLocked(failure);
                failFlushWaitersLocked();
            }
        }
        return null;
    }

    private void failQueuedLocked(Throwable failure) {
        while (!queue.isEmpty()) {
            Pending pending = queue.removeFirst();
            failed++;
            pending.future.completeExceptionally(failure);
        }
    }

    private void completeFlushWaitersLocked() {
        for (int index = flushWaiters.size() - 1; index >= 0; index--) {
            FlushWaiter waiter = flushWaiters.get(index);
            if (waiter.watermark <= durableSequence) {
                waiter.future.complete(null);
                flushWaiters.remove(index);
            }
        }
    }

    private void failFlushWaitersLocked() {
        for (FlushWaiter waiter : flushWaiters) {
            waiter.future.completeExceptionally(new IllegalStateException("Audit writer is unhealthy"));
        }
        flushWaiters.clear();
    }

    private void markUnhealthyLocked() {
        if (healthy) {
            healthy = false;
            LOGGER.error("Audit writer became unhealthy after an audit persistence failure");
        }
    }

    private void finishWaitersLocked() {
        if (draining || !queue.isEmpty()) {
            return;
        }
        if (!healthy) {
            failFlushWaitersLocked();
        }
        if (shutdownFuture != null && !shutdownFuture.isDone()) {
            if (healthy) {
                shutdownFuture.complete(null);
            } else {
                shutdownFuture.completeExceptionally(new IllegalStateException("Audit writer is unhealthy"));
            }
        }
    }

    private void logDropLocked() {
        long now = System.nanoTime();
        if (now - lastDropLogNanos >= 60_000_000_000L) {
            lastDropLogNanos = now;
            LOGGER.warn("Audit best-effort events are being dropped because the bounded writer queue is full");
        }
    }

    private static final class Pending {
        private final AuditEvent event;
        private final CompletableFuture<AuditWriteResult> future = new CompletableFuture<>();
        private long admissionSequence;

        private Pending(AuditEvent event) {
            this.event = event;
        }

        private AuditEvent event() {
            return event;
        }
    }

    private record FlushWaiter(long watermark, CompletableFuture<Void> future) {
    }
}
