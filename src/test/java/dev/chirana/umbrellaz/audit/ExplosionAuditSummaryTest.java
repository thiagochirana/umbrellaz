package dev.chirana.umbrellaz.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExplosionAuditSummaryTest {
    @Test
    void groupsChangesAndBoundsGroupCountAndChangedCount() {
        List<ExplosionAuditSummary.BlockChange> changes = new ArrayList<>();
        for (int index = 0; index < 140; index++) {
            changes.add(new ExplosionAuditSummary.BlockChange(
                    "minecraft:old_" + index, "minecraft:new_" + index));
        }
        changes.add(new ExplosionAuditSummary.BlockChange("minecraft:stone", "minecraft:air"));

        ExplosionAuditSummary.Summary summary = ExplosionAuditSummary.summarize(
                new ExplosionAuditSummary.Position(1, 64, -2), changes);

        assertNotNull(summary);
        assertEquals(128, summary.changedCount());
        assertEquals(16, summary.groups().size());
        assertEquals(1, summary.groups().getFirst().count());
    }

    @Test
    void sameRegistryPairCanRepresentAStateChange() {
        ExplosionAuditSummary.Summary summary = ExplosionAuditSummary.summarize(
                new ExplosionAuditSummary.Position(0, 0, 0),
                List.of(new ExplosionAuditSummary.BlockChange("minecraft:water", "minecraft:water")));

        assertNotNull(summary);
        assertEquals(1, summary.changedCount());
        assertEquals("minecraft:water", summary.groups().getFirst().oldType());
    }

    @Test
    void emptyChangesProduceNoSummary() {
        assertNull(ExplosionAuditSummary.summarize(
                new ExplosionAuditSummary.Position(0, 0, 0), List.of()));
    }

    @Test
    void payloadUsesBoundedTypedPairedLists() {
        ExplosionAuditSummary.Summary summary = ExplosionAuditSummary.summarize(
                new ExplosionAuditSummary.Position(1, 64, -2),
                List.of(new ExplosionAuditSummary.BlockChange("minecraft:oak_log", "minecraft:air"),
                        new ExplosionAuditSummary.BlockChange("minecraft:oak_log", "minecraft:air")));

        AuditPayload payload = AuditPayload.environmentBlocksChanged("minecraft:overworld", summary);

        assertEquals("explosion", payload.values().get("mechanism"));
        assertEquals("2", payload.values().get("changed_count"));
        assertEquals("minecraft:oak_log", payload.values().get("old_types"));
        assertEquals("minecraft:air", payload.values().get("new_types"));
        assertEquals("2", payload.values().get("group_counts"));
    }
}
