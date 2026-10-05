package dev.chirana.umbrellaz.player;

import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class FoodLockEvents {
    private FoodLockEvents() {
    }

    public static boolean isLocked(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return false;
        return ServerRuntimeRegistry.findReady(level.getServer())
                .map(runtime -> runtime.foodLockService().lockedFood(player.getUUID()).isPresent())
                .orElse(false);
    }
}
