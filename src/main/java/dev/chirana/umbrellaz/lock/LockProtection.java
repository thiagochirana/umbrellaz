package dev.chirana.umbrellaz.lock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public final class LockProtection {
    private final LockCache cache;
    private final LockPlacementTracker placements;

    LockProtection(LockCache cache, LockPlacementTracker placements) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.placements = Objects.requireNonNull(placements, "placements");
    }

    static Decision unavailableDecision(ServerLevel level, BlockPos position) {
        return LockBlockAdapter.classify(level.getBlockState(position)).isEmpty()
                ? Decision.NOT_SUPPORTED : Decision.NOT_READY;
    }

    Decision decision(ServerLevel level, BlockPos position) {
        if (LockBlockAdapter.classify(level.getBlockState(position)).isEmpty()) {
            return Decision.NOT_SUPPORTED;
        }
        if (!LockWorldIdentity.isReady() || !placements.isReady() || !cache.isReady()) {
            return Decision.NOT_READY;
        }
        return LockWorldIdentity.tryTarget(level, position)
                .map(cache::provenance)
                .map(provenance -> provenance.isPresent() ? Decision.LOCKED : Decision.UNLOCKED)
                .orElse(Decision.NOT_READY);
    }

    public enum Decision {
        NOT_SUPPORTED,
        NOT_READY,
        UNLOCKED,
        LOCKED;

        public boolean blocksEnvironmentalMutation() {
            return this != NOT_SUPPORTED && this != UNLOCKED;
        }
    }
}
