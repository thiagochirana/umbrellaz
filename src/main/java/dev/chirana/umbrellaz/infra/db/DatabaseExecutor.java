package dev.chirana.umbrellaz.infra.db;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class DatabaseExecutor implements AutoCloseable {
    private final ExecutorService executor;

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
        try {
            executor.execute(() -> {
                try {
                    future.complete(operation.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
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
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
