package dev.chirana.umbrellaz.lock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.UUID;

public final class LockWorldIdentity {
    private static final java.util.Map<MinecraftServer, UUID> WORLD_INSTANCE_IDS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private LockWorldIdentity() {
    }

    static void initialize(MinecraftServer server) {
        WORLD_INSTANCE_IDS.put(server, server.getDataStorage().computeIfAbsent(LockWorldInstanceData.TYPE).instanceId());
    }

    static boolean isReady() {
        return false;
    }

    static boolean isReady(ServerLevel level) {
        return WORLD_INSTANCE_IDS.containsKey(level.getServer());
    }

    static Optional<LockTarget> tryTarget(ServerLevel level, BlockPos position) {
        UUID instanceId = WORLD_INSTANCE_IDS.get(level.getServer());
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

    public static void clear(MinecraftServer server) {
        WORLD_INSTANCE_IDS.remove(server);
    }
}
