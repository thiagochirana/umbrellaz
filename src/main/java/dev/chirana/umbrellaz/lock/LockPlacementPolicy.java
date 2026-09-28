package dev.chirana.umbrellaz.lock;

import java.util.UUID;

public final class LockPlacementPolicy {
    public boolean isEligible(LockBlockType blockType) {
        return blockType != null;
    }

    public boolean isEligible(LockTarget intendedTarget, UUID installerUuid, LockBlockType expectedBlockType,
                              PlacementProvenance provenance) {
        return isEligible(expectedBlockType)
                && intendedTarget != null
                && installerUuid != null
                && provenance != null
                && intendedTarget.equals(provenance.target())
                && installerUuid.equals(provenance.placedBy())
                && expectedBlockType == provenance.blockType();
    }

    public boolean canGroupDoubleChest(PlacementProvenance first, LockBlockType firstType,
                                       PlacementProvenance second, LockBlockType secondType) {
        return first != null && second != null && firstType != null && firstType == secondType
                && (firstType == LockBlockType.CHEST || firstType == LockBlockType.TRAPPED_CHEST)
                && first.blockType() == firstType && second.blockType() == secondType
                && first.placedBy().equals(second.placedBy())
                && first.target().world().equals(second.target().world())
                && first.target().dimension().equals(second.target().dimension())
                && first.target().position().isHorizontallyAdjacentTo(second.target().position());
    }
}
