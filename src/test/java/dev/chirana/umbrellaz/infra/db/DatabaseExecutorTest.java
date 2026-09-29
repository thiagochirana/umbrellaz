package dev.chirana.umbrellaz.infra.db;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExecutorTest {
    @Test
    void closeDrainsQueuedWorkButRejectsNewSubmissions() throws Exception {
        DatabaseExecutor executor = new DatabaseExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> completed = new ArrayList<>();

        CompletableFuture<Void> first = executor.submit(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> critical = executor.submit(() -> {
            completed.add("critical");
        });
        executor.close();
        CompletableFuture<Void> rejected = executor.submit(() -> {
            completed.add("rejected");
        });

        assertThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
        releaseFirst.countDown();

        first.get(5, TimeUnit.SECONDS);
        critical.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("critical"), completed);
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
