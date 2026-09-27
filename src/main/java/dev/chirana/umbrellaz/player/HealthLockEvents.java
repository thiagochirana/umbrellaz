package dev.chirana.umbrellaz.player;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import java.util.UUID;

public final class HealthLockEvents {
    private static volatile HealthLockService runtimeService;

    private HealthLockEvents() {
    }

    public static void register(HealthLockService healthLockService) {
        runtimeService = healthLockService;
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> healthLockService.clear(handler.player.getUUID()));
    }

    public static boolean isLocked(UUID playerUuid) {
        HealthLockService healthLockService = runtimeService;
        return healthLockService != null && healthLockService.lockedHealth(playerUuid).isPresent();
    }

    public static float lockedHealth(UUID playerUuid) {
        HealthLockService healthLockService = runtimeService;
        if (healthLockService == null) {
            return 0.0f;
        }
        return healthLockService.lockedHealth(playerUuid).orElse(0.0f);
    }
}
