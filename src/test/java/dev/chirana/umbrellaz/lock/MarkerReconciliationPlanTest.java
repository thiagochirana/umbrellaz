package dev.chirana.umbrellaz.lock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MarkerReconciliationPlanTest {
    @Test
    void retainsOneCorrectKeeperAndCreatesNothing() {
        MarkerReconciliationPlan.Position expected = new MarkerReconciliationPlan.Position(4, 9, 2);
        MarkerReconciliationPlan.Plan plan = MarkerReconciliationPlan.plan(expected, List.of(
                new MarkerReconciliationPlan.Position(4, 9, 2),
                new MarkerReconciliationPlan.Position(4, 9, 2),
                new MarkerReconciliationPlan.Position(20, 9, 2)));

        assertEquals(0, plan.keeperIndex());
        assertEquals(List.of(1, 2), plan.discardIndices());
        assertFalse(plan.create());
    }

    @Test
    void discardsMisplacedMarkersAndPlansExactlyOneReplacement() {
        MarkerReconciliationPlan.Position expected = new MarkerReconciliationPlan.Position(4, 9, 2);
        MarkerReconciliationPlan.Plan plan = MarkerReconciliationPlan.plan(expected, List.of(
                new MarkerReconciliationPlan.Position(20, 9, 2),
                new MarkerReconciliationPlan.Position(21, 9, 2)));

        assertEquals(-1, plan.keeperIndex());
        assertEquals(List.of(0, 1), plan.discardIndices());
        assertTrue(plan.create());
    }

    @Test
    void discardsLegacyMarkerAboveTargetAndPlansFrontFaceReplacement() {
        MarkerReconciliationPlan.Position targetBlock = new MarkerReconciliationPlan.Position(4, 8, 2);
        MarkerReconciliationPlan.Plan plan = MarkerReconciliationPlan.plan(targetBlock, List.of(
                new MarkerReconciliationPlan.Position(4, 9, 2)));

        assertEquals(-1, plan.keeperIndex());
        assertEquals(List.of(0), plan.discardIndices());
        assertTrue(plan.create());
    }

    @Test
    void staleLockerWithoutExpectedPositionOnlyDiscardsProjection() {
        MarkerReconciliationPlan.Plan plan = MarkerReconciliationPlan.plan(null, List.of(
                new MarkerReconciliationPlan.Position(4, 9, 2)));

        assertEquals(-1, plan.keeperIndex());
        assertEquals(List.of(0), plan.discardIndices());
        assertFalse(plan.create());
    }

    @Test
    void affectedTargetCleanupPlansOnlyLoadedTargetChunks() {
        List<MarkerReconciliationPlan.Chunk> chunks = MarkerReconciliationPlan.affectedTargetChunks(List.of(
                new MarkerReconciliationPlan.Position(4, 9, 2),
                new MarkerReconciliationPlan.Position(15, 9, 15),
                new MarkerReconciliationPlan.Position(16, 9, 15)));

        assertEquals(List.of(
                new MarkerReconciliationPlan.Chunk(0, 0),
                new MarkerReconciliationPlan.Chunk(1, 0)), chunks);
    }
}
