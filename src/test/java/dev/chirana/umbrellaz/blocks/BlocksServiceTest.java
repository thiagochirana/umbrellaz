package dev.chirana.umbrellaz.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksServiceTest {
    @Test
    void placementPositionUsesContextPositionWithoutAddingFaceOffset() {
        BlockPos.MutableBlockPos contextPosition = new BlockPos.MutableBlockPos(4, 20, -8);

        BlockPos actualPosition = BlocksEvents.actualPlacementPosition(contextPosition);
        contextPosition.set(4, 21, -8);

        assertEquals(new BlockPos(4, 20, -8), actualPosition);
        assertNotSame(contextPosition, actualPosition);
    }

    @Test
    void treeCascadeRequiresEnabledShiftHeldRawLogBreak() {
        assertFalse(BlocksService.shouldCascadeTreeBreak(false, true, true));
        assertFalse(BlocksService.shouldCascadeTreeBreak(true, false, true));
        assertTrue(BlocksService.shouldCascadeTreeBreak(true, true, true));
        assertFalse(BlocksService.shouldCascadeTreeBreak(true, true, false));
    }

    @Test
    void treeCascadeMatchesTheInitialLogType() {
        Object oakLog = new Object();
        Object birchLog = new Object();

        assertTrue(BlocksService.sameBlock(oakLog, oakLog));
        assertFalse(BlocksService.sameBlock(oakLog, birchLog));
    }

    @Test
    void treeClassifierAcceptsRawWoodAndRejectsProcessedWoodAndStems() {
        assertTrue(BlocksService.isRawTreeLogIdentifier(
                Identifier.fromNamespaceAndPath("minecraft", "oak_log")));
        assertTrue(BlocksService.isRawTreeLogIdentifier(
                Identifier.fromNamespaceAndPath("minecraft", "oak_wood")));
        assertFalse(BlocksService.isRawTreeLogIdentifier(
                Identifier.fromNamespaceAndPath("minecraft", "stripped_oak_log")));
        assertFalse(BlocksService.isRawTreeLogIdentifier(
                Identifier.fromNamespaceAndPath("minecraft", "stripped_oak_wood")));
        assertFalse(BlocksService.isRawTreeLogIdentifier(
                Identifier.fromNamespaceAndPath("minecraft", "crimson_stem")));
        assertFalse(BlocksService.isRawTreeLogIdentifier(
                Identifier.fromNamespaceAndPath("minecraft", "warped_hyphae")));
    }

    @Test
    void oreCascadeRequiresEnabledShiftHeldOreBreak() {
        assertFalse(BlocksService.shouldCascadeOreBreak(false, true, true));
        assertFalse(BlocksService.shouldCascadeOreBreak(true, false, true));
        assertTrue(BlocksService.shouldCascadeOreBreak(true, true, true));
        assertFalse(BlocksService.shouldCascadeOreBreak(true, true, false));
    }

    @Test
    void oreCascadeRejectsNonOreBlocks() {
        assertFalse(BlocksService.shouldCascadeOreBreak(true, true, false));
    }
}
