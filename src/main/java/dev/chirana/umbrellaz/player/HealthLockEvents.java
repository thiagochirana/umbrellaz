package dev.chirana.umbrellaz.player;

import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class HealthLockEvents {
    private HealthLockEvents() {
    }

    public static boolean isLocked(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return false;
        return ServerRuntimeRegistry.findReady(level.getServer())
                .map(runtime -> runtime.healthLockService().lockedHealth(player.getUUID()).isPresent())
                .orElse(false);
    }

    public static float lockedHealth(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return 0.0f;
        return ServerRuntimeRegistry.findReady(level.getServer())
                .flatMap(runtime -> runtime.healthLockService().lockedHealth(player.getUUID()))
                .orElse(0.0f);
    }

    public static boolean isLocked(UUID playerUuid) {
        return false;
    }

    public static float lockedHealth(UUID playerUuid) {
        return 0.0f;
    }
}
