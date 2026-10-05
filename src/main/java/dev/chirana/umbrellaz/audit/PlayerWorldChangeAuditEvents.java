package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Global adapter for confirmed player dimension changes. */
public final class PlayerWorldChangeAuditEvents {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private PlayerWorldChangeAuditEvents() {}

    public static void installGlobalCallbacks() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                PlayerWorldChangeAuditEvents::afterPlayerChangeLevel);
    }

    public static void afterPlayerChangeLevel(ServerPlayer player, ServerLevel source, ServerLevel destination) {
        if (player == null || source == null || destination == null) return;
        ServerRuntimeRegistry.findReady(destination.getServer()).ifPresent(runtime -> {
            try {
                runtime.auditService().record(request(player.getUUID(), player.getName().getString(),
                        source.dimension().identifier().toString(), destination.dimension().identifier().toString(),
                        player.getBlockX(), player.getBlockY(), player.getBlockZ())).exceptionally(failure -> null);
            } catch (RuntimeException ignored) {
                // Auditing must not interfere with a completed dimension change.
            }
        });
    }

    public static AuditRecordRequest request(UUID playerUuid, String playerName, String sourceWorld,
                                             String destinationWorld, int x, int y, int z) {
        AuditPayload payload = AuditPayload.forAction(AuditActions.PLAYER_WORLD_CHANGED,
                AuditPayload.playerUuid(playerUuid), AuditPayload.playerName(playerName),
                AuditPayload.sourceWorld(sourceWorld), AuditPayload.targetWorld(destinationWorld),
                AuditPayload.targetPosition(x, y, z));
        return new AuditRecordRequest(AuditRecordContext.forActor(
                new Actor(ActorType.SYSTEM, null, "server")), Source.SYSTEM,
                AuditActions.PLAYER_WORLD_CHANGED, Outcome.SUCCESS,
                new Target("player", playerUuid.toString()), "world_changed", 1, payload,
                AuditDelivery.BEST_EFFORT);
    }
}
