package dev.chirana.umbrellaz.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageAuditAggregatorTest {
    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void equalKeysMergeAndDifferentKeysSeparateAtWindowBoundary() {
        List<AuditRecordRequest> requests = new ArrayList<>();
        DamageAuditAggregator aggregator = new DamageAuditAggregator(
                request -> { requests.add(request); return CompletableFuture.completedFuture(null); }, 100, 10);
        aggregator.observe(observation(ATTACKER, VICTIM, null, 2, 1), 0);
        aggregator.observe(observation(ATTACKER, VICTIM, null, 3, 2), 50);
        aggregator.observe(observation(ATTACKER, null, "minecraft:zombie", 4, 3), 50);

        assertEquals(2, aggregator.bucketCount());
        assertEquals(2, aggregator.flushIfDue(151));
        assertEquals(2, requests.size());
        assertTrue(requests.stream().anyMatch(request -> request.payload().values().get("count").equals("2")));
        assertTrue(requests.stream().anyMatch(request -> request.payload().values().get("count").equals("1")));
    }

    @Test
    void filtersNoEffectShieldAndNonPlayerObservations() {
        List<AuditRecordRequest> requests = new ArrayList<>();
        DamageAuditAggregator aggregator = new DamageAuditAggregator(
                request -> { requests.add(request); return CompletableFuture.completedFuture(null); }, 100, 10);
        aggregator.observe(observation(null, null, "minecraft:zombie", 2, 1), 0);
        aggregator.observe(new DamageAuditObservation(ATTACKER, VICTIM, null, "melee", "minecraft:air",
                "minecraft:overworld", 2, 0, false), 0);
        aggregator.observe(new DamageAuditObservation(ATTACKER, VICTIM, null, "melee", "minecraft:air",
                "minecraft:overworld", 2, 1, true), 0);
        assertEquals(0, aggregator.bucketCount());
        assertEquals(0, aggregator.flushIfDue(100));
        assertTrue(requests.isEmpty());
    }

    @Test
    void capacityAndCloseAreObservableAndFinalSummaryIsSubmitted() {
        List<AuditRecordRequest> requests = new ArrayList<>();
        DamageAuditAggregator aggregator = new DamageAuditAggregator(
                request -> { requests.add(request); return CompletableFuture.completedFuture(null); }, 100, 1);
        aggregator.observe(observation(ATTACKER, VICTIM, null, 1, 1), 0);
        aggregator.observe(observation(ATTACKER, null, "minecraft:zombie", 1, 1), 1);
        assertEquals(1, aggregator.overflowDrops());
        assertEquals(1, aggregator.closeAndFlush(10));
        assertTrue(aggregator.closed());
        aggregator.observe(observation(ATTACKER, VICTIM, null, 1, 1), 11);
        assertEquals(1, aggregator.closedDrops());
        assertEquals(1, requests.size());
    }

    @Test
    void damagePayloadUsesBoundedTypedFields() {
        List<AuditRecordRequest> requests = new ArrayList<>();
        DamageAuditAggregator aggregator = new DamageAuditAggregator(
                request -> { requests.add(request); return CompletableFuture.completedFuture(null); }, 10, 2);
        aggregator.observe(observation(ATTACKER, VICTIM, null, 2.5, 1.25), 100);
        aggregator.closeAndFlush(125);
        AuditPayload payload = requests.getFirst().payload();
        assertEquals("2.5", payload.values().get("base_damage"));
        assertEquals("1.25", payload.values().get("reported_damage"));
        assertFalse(payload.values().containsKey("health_loss"));
        assertTrue(payload.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= AuditPayload.MAX_JSON_BYTES);
    }

    private static DamageAuditObservation observation(UUID attacker, UUID victim, String entityType,
                                                      double base, double reported) {
        if (victim != null) entityType = null;
        return new DamageAuditObservation(attacker, victim, entityType, "entity_attack", "minecraft:air",
                "minecraft:overworld", base, reported, false);
    }
}
