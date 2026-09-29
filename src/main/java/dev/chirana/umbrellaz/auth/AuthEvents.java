package dev.chirana.umbrellaz.auth;

import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.config.UmbrellazConfig;
import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AuthEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthEvents.class);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private AuthEvents() {
    }

    public static void installGlobalCallbacks() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> blocked(player)
                ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> blocked(player)
                ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> blocked(player)
                ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> blocked(player)
                ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) -> blocked(player)
                ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !blocked(player));
        ServerLifecycleEvents.SERVER_STARTED.register(AuthEvents::validateRestrictedSpawn);
    }

    public static boolean isBlocked(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return true;
        return ServerRuntimeRegistry.findReady(level.getServer())
                .map(runtime -> !runtime.isInteractionAllowed(player))
                .orElse(true);
    }

    public static boolean isBlocked(Player player) {
        return player instanceof ServerPlayer serverPlayer && isBlocked(serverPlayer);
    }

    public static boolean isBlocked(UUID uuid) {
        return true;
    }

    public static boolean isAdministrator(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return false;
        return isAdministrator(level.getServer(), player.getUUID());
    }

    public static boolean isAdministrator(MinecraftServer server, UUID uuid) {
        return ServerRuntimeRegistry.findReady(server)
                .map(runtime -> runtime.authorizationService().isAdministrator(uuid))
                .orElse(false);
    }

    public static boolean isAdministrator(UUID uuid) {
        return false;
    }

    public static void teleportToRestrictedSpawn(MinecraftServer server, ServerPlayer player, UmbrellazConfig config) {
        UmbrellazConfig.RestrictedSpawn spawn = config.restrictedSpawn();
        Identifier identifier = Identifier.tryParse(spawn.world());
        if (identifier == null) return;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, identifier);
        ServerLevel world = server.getLevel(key);
        if (world != null) {
            player.teleportTo(world, spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), spawn.pitch(), true);
        }
    }

    private static boolean blocked(Player player) {
        return isBlocked(player);
    }

    private static void validateRestrictedSpawn(MinecraftServer server) {
        ServerRuntimeRegistry.find(server).ifPresent(runtime -> {
            UmbrellazConfig config = runtime.config();
            Identifier identifier = Identifier.tryParse(config.restrictedSpawn().world());
            if (identifier == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, identifier)) == null) {
                LOGGER.error("Restricted spawn world is unavailable: {}", config.restrictedSpawn().world());
            }
        });
    }
}
