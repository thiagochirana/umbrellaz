package dev.chirana.umbrellaz.mixin;

import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditInteractionSupportTest {
    @Test
    void onlyConsumingInteractionResultsAreAccepted() {
        assertTrue(AuditInteractionSupport.accepted(InteractionResult.SUCCESS));
        assertTrue(AuditInteractionSupport.accepted(InteractionResult.SUCCESS_SERVER));
        assertTrue(AuditInteractionSupport.accepted(InteractionResult.CONSUME));
        assertFalse(AuditInteractionSupport.accepted(InteractionResult.PASS));
        assertFalse(AuditInteractionSupport.accepted(InteractionResult.FAIL));
        assertFalse(AuditInteractionSupport.accepted(null));
    }

    @Test
    void containerFingerprintsCompareOnlyBoundedValues() {
        AuditInteractionSupport.ContainerFingerprint first = new AuditInteractionSupport.ContainerFingerprint(
                "minecraft.chestmenu", 4, "minecraft:stone", 1, 2, "minecraft:stone", 1, true);
        AuditInteractionSupport.ContainerFingerprint same = new AuditInteractionSupport.ContainerFingerprint(
                "minecraft.chestmenu", 4, "minecraft:stone", 1, 2, "minecraft:stone", 1, true);
        AuditInteractionSupport.ContainerFingerprint changed = new AuditInteractionSupport.ContainerFingerprint(
                "minecraft.chestmenu", 5, "minecraft:stone", 1, 2, "minecraft:stone", 1, true);

        assertFalse(AuditInteractionSupport.fingerprintsChanged(first, same));
        assertTrue(AuditInteractionSupport.fingerprintsChanged(first, changed));
    }

    @Test
    void menuTypeIsNormalizedAndPathBounded() {
        String menuType = AuditInteractionSupport.boundedMenuType("a" + ".very-long-menu".repeat(40));
        assertTrue(menuType.length() <= 128);
        assertTrue(menuType.matches("[a-z0-9][a-z0-9._:/-]*"));
        assertEquals("unknown", AuditInteractionSupport.boundedMenuType("😀"));
    }
}
