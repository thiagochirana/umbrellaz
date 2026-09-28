package dev.chirana.umbrellaz.lock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.UUID;

final class LockWorldIdentity {
    private static volatile UUID worldInstanceId;

    private LockWorldIdentity() {
    }

    static void initialize(MinecraftServer server) {
        worldInstanceId = null;
        worldInstanceId = server.getDataStorage().computeIfAbsent(LockWorldInstanceData.TYPE).instanceId();
    }

    static boolean isReady() {
        return worldInstanceId != null;
    }

    static Optional<LockTarget> tryTarget(ServerLevel level, BlockPos position) {
        UUID instanceId = worldInstanceId;
        if (instanceId == null) {
            return Optional.empty();
        }
        return Optional.of(new LockTarget(new WorldIdentity(instanceId.toString()),
                new DimensionIdentity(level.dimension().identifier().toString()),
                new BlockPosition(position.getX(), position.getY(), position.getZ())));
    }

    static LockTarget target(ServerLevel level, BlockPos position) {
        return tryTarget(level, position)
                .orElseThrow(() -> new IllegalStateException("Lock world identity is not initialized"));
    }
}
