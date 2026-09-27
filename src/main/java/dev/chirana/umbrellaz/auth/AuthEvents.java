package dev.chirana.umbrellaz.auth;

import dev.chirana.umbrellaz.config.UmbrellazConfig;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuthEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthEvents.class);
    private static volatile AuthService runtimeAuthService;
    private static volatile AuthorizationService runtimeAuthorizationService;

    private AuthEvents() {
    }

    public static void register(AuthService authService, AuthorizationService authorizationService, UmbrellazConfig config) {
        runtimeAuthService = authService;
        runtimeAuthorizationService = authorizationService;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            authService.onJoin(player.getUUID(), player.getName().getString()).thenAccept(authenticated ->
                    server.execute(() -> {
                        if (!authenticated && player.isAlive()) {
                            teleportToRestrictedSpawn(server, player, config);
                        }
                    }));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> authService.onDisconnect(handler.player.getUUID()));
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> blocked(player, authService) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blocked(player, authService) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> blocked(player, authService) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blocked(player, authService) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) -> blocked(player, authService)
                ? InteractionResult.FAIL
                : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !blocked(player, authService));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> validateRestrictedSpawn(server, config));
    }

    public static boolean isBlocked(java.util.UUID uuid) {
        AuthService authService = runtimeAuthService;
        return authService == null || authService.isBlocked(uuid);
    }

    public static boolean isAdministrator(java.util.UUID uuid) {
        AuthorizationService authorizationService = runtimeAuthorizationService;
        return authorizationService != null && authorizationService.isAdministrator(uuid);
    }

    public static void teleportToRestrictedSpawn(MinecraftServer server, ServerPlayer player, UmbrellazConfig config) {
        UmbrellazConfig.RestrictedSpawn spawn = config.restrictedSpawn();
        Identifier identifier = Identifier.tryParse(spawn.world());
        if (identifier == null) {
            return;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, identifier);
        ServerLevel world = server.getLevel(key);
        if (world != null) {
            player.teleportTo(world, spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), spawn.pitch(), true);
        }
    }

    private static boolean blocked(Player player, AuthService authService) {
        return authService.isBlocked(player.getUUID());
    }

    private static void validateRestrictedSpawn(MinecraftServer server, UmbrellazConfig config) {
        Identifier identifier = Identifier.tryParse(config.restrictedSpawn().world());
        if (identifier == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, identifier)) == null) {
            LOGGER.error("Restricted spawn world is unavailable: {}", config.restrictedSpawn().world());
        }
    }
}
