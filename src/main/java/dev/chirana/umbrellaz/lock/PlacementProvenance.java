package dev.chirana.umbrellaz.lock;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlacementProvenance(LockTarget target, UUID generationId, UUID placedBy,
                                  LockBlockType blockType, Instant placedAt) {
    public PlacementProvenance {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(placedBy, "placedBy");
        Objects.requireNonNull(blockType, "blockType");
        Objects.requireNonNull(placedAt, "placedAt");
    }

}
