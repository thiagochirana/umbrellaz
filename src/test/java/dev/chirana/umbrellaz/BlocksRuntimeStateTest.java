package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.blocks.BlocksRuntimeState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksRuntimeStateTest {
    @Test
    void togglesTreeAndOreAutomationIndependently() {
        BlocksRuntimeState state = new BlocksRuntimeState();

        assertFalse(state.treeEzBreakEnabled());
        assertFalse(state.oresEzBreakEnabled());

        state.setTreeEzBreakEnabled(true);
        state.setOresEzBreakEnabled(true);
        assertTrue(state.treeEzBreakEnabled());
        assertTrue(state.oresEzBreakEnabled());

        state.setTreeEzBreakEnabled(false);
        assertFalse(state.treeEzBreakEnabled());
        assertTrue(state.oresEzBreakEnabled());
    }
}
