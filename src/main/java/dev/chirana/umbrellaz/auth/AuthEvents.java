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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

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
            ServerPlayerEntity player = handler.player;
            authService.onJoin(player.getUuid(), player.getName().getString()).thenAccept(authenticated ->
                    server.execute(() -> {
                        if (!authenticated && player.isAlive()) {
                            teleportToRestrictedSpawn(server, player, config);
                        }
                    }));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> authService.onDisconnect(handler.player.getUuid()));
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> blocked(player, authService) ? ActionResult.FAIL : ActionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blocked(player, authService) ? ActionResult.FAIL : ActionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> blocked(player, authService) ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blocked(player, authService) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) -> blocked(player, authService)
                ? TypedActionResult.fail(player.getStackInHand(hand))
                : TypedActionResult.pass(player.getStackInHand(hand)));
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

    public static void teleportToRestrictedSpawn(MinecraftServer server, ServerPlayerEntity player, UmbrellazConfig config) {
        UmbrellazConfig.RestrictedSpawn spawn = config.restrictedSpawn();
        Identifier identifier = Identifier.tryParse(spawn.world());
        if (identifier == null) {
            return;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, identifier);
        ServerWorld world = server.getWorld(key);
        if (world != null) {
            player.teleport(world, spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), spawn.pitch());
        }
    }

    private static boolean blocked(PlayerEntity player, AuthService authService) {
        return authService.isBlocked(player.getUuid());
    }

    private static void validateRestrictedSpawn(MinecraftServer server, UmbrellazConfig config) {
        Identifier identifier = Identifier.tryParse(config.restrictedSpawn().world());
        if (identifier == null || server.getWorld(RegistryKey.of(RegistryKeys.WORLD, identifier)) == null) {
            LOGGER.error("Restricted spawn world is unavailable: {}", config.restrictedSpawn().world());
        }
    }
}
