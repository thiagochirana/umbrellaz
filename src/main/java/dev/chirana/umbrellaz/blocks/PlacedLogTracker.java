package dev.chirana.umbrellaz.blocks;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PlacedLogTracker {
    private final Set<PlacementKey> placedLogs = new HashSet<>();

    // Placement and break callbacks run on the server thread, so this state does not need synchronization.

    public void record(ResourceKey<Level> dimension, BlockPos position, boolean isLog) {
        PlacementKey key = new PlacementKey(dimension, position.asLong());
        if (isLog) {
            placedLogs.add(key);
        } else {
            placedLogs.remove(key);
        }
    }

    public boolean remove(ResourceKey<Level> dimension, BlockPos position) {
        return placedLogs.remove(new PlacementKey(dimension, position.asLong()));
    }

    public boolean contains(ResourceKey<Level> dimension, BlockPos position) {
        return placedLogs.contains(new PlacementKey(dimension, position.asLong()));
    }

    public boolean shouldSkipTreeTraversal(ResourceKey<Level> dimension, BlockPos position, boolean isLog) {
        if (!contains(dimension, position)) {
            return false;
        }
        if (!isLog) {
            remove(dimension, position);
            return false;
        }
        return true;
    }

    private record PlacementKey(ResourceKey<Level> dimension, long position) {
    }
}
