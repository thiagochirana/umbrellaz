package dev.chirana.umbrellaz.lock;

import java.util.Objects;
import java.util.UUID;

public record LockerMember(UUID lockerId, LockTarget target, UUID generationId,
                           UUID placedBy, LockBlockType blockType) {
    public LockerMember {
        Objects.requireNonNull(lockerId, "lockerId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(placedBy, "placedBy");
        Objects.requireNonNull(blockType, "blockType");
    }
}
