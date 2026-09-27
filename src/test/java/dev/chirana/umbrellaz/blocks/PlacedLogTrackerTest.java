package dev.chirana.umbrellaz.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedLogTrackerTest {
    private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
            Identifier.fromNamespaceAndPath("umbrellaz-test", "dimensions"));
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
            DIMENSION_REGISTRY, Identifier.fromNamespaceAndPath("umbrellaz-test", "overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(
            DIMENSION_REGISTRY, Identifier.fromNamespaceAndPath("umbrellaz-test", "nether"));
    private final PlacedLogTracker tracker = new PlacedLogTracker();
    private final BlockPos position = new BlockPos(4, 20, -8);

    @Test
    void tracksAndRemovesPlacedLogPositions() {
        tracker.record(OVERWORLD, position, true);

        assertTrue(tracker.contains(OVERWORLD, position));
        assertTrue(tracker.remove(OVERWORLD, position));
        assertFalse(tracker.contains(OVERWORLD, position));
        assertFalse(tracker.remove(OVERWORLD, position));
    }

    @Test
    void keepsDimensionsIsolated() {
        tracker.record(OVERWORLD, position, true);

        assertFalse(tracker.contains(NETHER, position));
        assertFalse(tracker.remove(NETHER, position));
        assertTrue(tracker.contains(OVERWORLD, position));
    }

    @Test
    void copiesMutablePositionValue() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        tracker.record(OVERWORLD, mutable, true);
        mutable.set(9, 9, 9);

        assertTrue(tracker.contains(OVERWORLD, new BlockPos(1, 2, 3)));
        assertFalse(tracker.contains(OVERWORLD, mutable));
    }

    @Test
    void leavesNaturalLogsEligibleAndPlacedLogsAsTraversalBarriers() {
        assertFalse(tracker.shouldSkipTreeTraversal(OVERWORLD, position, true));

        tracker.record(OVERWORLD, position, true);
        assertTrue(tracker.shouldSkipTreeTraversal(OVERWORLD, position, true));
        assertTrue(tracker.contains(OVERWORLD, position));
    }

    @Test
    void initialPlacedLogIsConsumedWithoutStartingCascade() {
        tracker.record(OVERWORLD, position, true);

        assertTrue(tracker.remove(OVERWORLD, position));
        assertFalse(tracker.contains(OVERWORLD, position));
    }

    @Test
    void replacementWithNonLogRemovesMarker() {
        tracker.record(OVERWORLD, position, true);
        tracker.record(OVERWORLD, position, false);

        assertFalse(tracker.contains(OVERWORLD, position));
        assertFalse(tracker.shouldSkipTreeTraversal(OVERWORLD, position, true));
    }

    @Test
    void staleMarkerForNonLogIsRemovedDuringTraversalCheck() {
        tracker.record(OVERWORLD, position, true);

        assertFalse(tracker.shouldSkipTreeTraversal(OVERWORLD, position, false));
        assertFalse(tracker.contains(OVERWORLD, position));
    }
}
