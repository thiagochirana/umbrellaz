package dev.chirana.umbrellaz.lock;

import java.util.Objects;

public record LockTarget(WorldIdentity world, DimensionIdentity dimension, BlockPosition position) {
    public LockTarget {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
    }
}
