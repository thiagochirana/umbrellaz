package dev.chirana.umbrellaz.protocol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ActionContextRegistryTest {
    @Test
    void contextHasOneOpaqueWireTokenAndCannotReplay() {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context context = registry.open(connection, player, nonce, 1,
                "lock", "target", Duration.ofSeconds(1));

        assertNotNull(context.token());
        assertTrue(registry.claim(context.token(), connection, player, nonce, 1).isPresent());
        assertTrue(registry.claim(context.token(), connection, player, nonce, 1).isEmpty());
         assertTrue(registry.complete(context));
         assertTrue(registry.complete(context));
    }

    @Test
    void claimRequiresEveryBindingField() {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context context = registry.open(connection, player, nonce, 7,
                "lock", "target", Duration.ofSeconds(1));

        assertTrue(registry.claim(context.token(), UUID.randomUUID(), player, nonce, 7).isEmpty());
        assertTrue(registry.claim(context.token(), connection, UUID.randomUUID(), nonce, 7).isEmpty());
        assertTrue(registry.claim(context.token(), connection, player, UUID.randomUUID(), 7).isEmpty());
        assertTrue(registry.claim(context.token(), connection, player, nonce, 8).isEmpty());
        assertEquals(ActionContextRegistry.ContextState.OPEN, context.state());
        assertEquals("lock", registry.claim(context.token(), connection, player, nonce, 7).orElseThrow().action());
        assertEquals("target", context.target());
    }

    @Test
    void invalidatingOldConnectionDoesNotInvalidateReconnectedPlayer() {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        UUID oldConnection = UUID.randomUUID();
        UUID newConnection = UUID.randomUUID();
        ActionContextRegistry.Context oldContext = registry.open(oldConnection, player, nonce, 1,
                "lock", "old", Duration.ofSeconds(1));
        ActionContextRegistry.Context newContext = registry.open(newConnection, player, nonce, 2,
                "lock", "new", Duration.ofSeconds(1));

        registry.invalidateConnection(oldConnection);

        assertEquals(ActionContextRegistry.ContextState.INVALIDATED, oldContext.state());
        assertTrue(registry.claim(newContext.token(), newConnection, player, nonce, 2).isPresent());
    }

    @Test
    void shutdownInvalidatesEveryConnectionContext() {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context first = registry.open(UUID.randomUUID(), player, nonce, 1,
                "lock", "first", Duration.ofSeconds(1));
        ActionContextRegistry.Context second = registry.open(UUID.randomUUID(), player, nonce, 2,
                "lock", "second", Duration.ofSeconds(1));

        registry.invalidateAll();

        assertEquals(ActionContextRegistry.ContextState.INVALIDATED, first.state());
        assertEquals(ActionContextRegistry.ContextState.INVALIDATED, second.state());
        assertTrue(registry.claim(first.token(), first.connectionId(), player, nonce, 1).isEmpty());
        assertTrue(registry.claim(second.token(), second.connectionId(), player, nonce, 2).isEmpty());
    }

    @Test
    void onlyOneConcurrentClaimWins() throws Exception {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context context = registry.open(connection, player, nonce,
                1, "lock", "target", Duration.ofSeconds(1));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return registry.claim(
                    context.token(), connection, player, nonce, 1).isPresent(); });
            var second = executor.submit(() -> { start.await(); return registry.claim(
                    context.token(), connection, player, nonce, 1).isPresent(); });
            start.countDown();
            assertNotEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationRequiresSessionBindingAndCanCancelInFlightContext() {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context context = registry.open(connection, player, nonce,
                1, "lock", "target", Duration.ofSeconds(1));

        assertFalse(registry.cancel(context.token(), UUID.randomUUID(), player, nonce, 1));
        assertFalse(registry.cancel(context.token(), connection, UUID.randomUUID(), nonce, 1));
        assertFalse(registry.cancel(context.token(), connection, player, UUID.randomUUID(), 1));
        assertFalse(registry.cancel(context.token(), connection, player, nonce, 2));
        assertEquals(ActionContextRegistry.ContextState.OPEN, context.state());

        ActionContextRegistry.Context claimed = registry.claim(context.token(), connection, player,
                nonce, 1).orElseThrow();
        assertTrue(registry.cancel(claimed.token(), connection, player, nonce, 1));
        assertEquals(ActionContextRegistry.ContextState.CANCELLED, context.state());
        assertFalse(registry.complete(claimed));
        assertTrue(registry.cancel(context.token(), connection, player, nonce, 1));
    }

    @Test
    void completionRequiresClaimedContextFromThisRegistry() {
        ActionContextRegistry registry = new ActionContextRegistry();
        ActionContextRegistry otherRegistry = new ActionContextRegistry();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context context = registry.open(connection, player, nonce,
                1, "lock", "target", Duration.ofSeconds(1));
        ActionContextRegistry.Context otherContext = otherRegistry.open(connection, player, nonce,
                1, "lock", "target", Duration.ofSeconds(1));

        assertFalse(registry.complete(context));
        registry.claim(context.token(), connection, player, nonce, 1).orElseThrow();
        assertTrue(registry.complete(context, connection, player, nonce, 1));
        assertFalse(registry.complete(otherContext));
    }

    @Test
    void expiredContextsCannotBeClaimed() {
        ActionContextRegistry registry = new ActionContextRegistry();
        UUID connection = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionContextRegistry.Context context = registry.open(connection, player, nonce, 1,
                "lock", "target", Duration.ofNanos(1));
        while (context.state() == ActionContextRegistry.ContextState.OPEN) {
            Thread.onSpinWait();
        }
        assertTrue(registry.claim(context.token(), connection, player, nonce, 1).isEmpty());
        assertEquals(ActionContextRegistry.ContextState.EXPIRED, context.state());
    }
}
