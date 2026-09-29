package dev.chirana.umbrellaz.infra.db;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class DatabaseExecutor implements AutoCloseable {
    private final ExecutorService executor;
    private final Object lifecycleLock = new Object();
    private boolean closed;

    public DatabaseExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "umbrellaz-db");
            thread.setDaemon(false);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        SubmittedTask<T> task = new SubmittedTask<>(operation, future);
        synchronized (lifecycleLock) {
            if (closed) {
                future.completeExceptionally(new RejectedExecutionException(
                        "Database executor is shut down"));
                return future;
            }
            try {
                executor.execute(task);
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        }
        return future;
    }

    public CompletableFuture<Void> submit(Runnable operation) {
        return submit(() -> {
            operation.run();
            return null;
        });
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            executor.shutdown();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    public boolean awaitTermination() throws InterruptedException {
        try {
            return executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static final class SubmittedTask<T> implements Runnable {
        private final Supplier<T> operation;
        private final CompletableFuture<T> future;

        private SubmittedTask(Supplier<T> operation, CompletableFuture<T> future) {
            this.operation = operation;
            this.future = future;
        }

        @Override
        public void run() {
            try {
                future.complete(operation.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }

    }
}
