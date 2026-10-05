package dev.chirana.umbrellaz.blocks;

import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.audit.Target;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

public final class BlocksEvents {
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED = new java.util.concurrent.atomic.AtomicBoolean();

    private BlocksEvents() {
    }

    public static void installGlobalCallbacks() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) -> {
            if (world instanceof ServerLevel serverLevel) {
                dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                        .ifPresent(runtime -> {
                            runtime.blocksService().afterSuccessfulBreak(world, player, position, state);
                            recordBreak(runtime.auditService(), serverLevel, player, position, state);
                        });
            }
        });
    }

    public static void afterSuccessfulPlayerPlacement(Level world, Player player, BlockPos position,
                                                      BlockState placedState) {
        afterSuccessfulPlayerPlacement(world, player, position, placedState, placedState.getBlock().asItem());
    }

    public static void afterSuccessfulPlayerPlacement(Level world, Player player, BlockPos position,
                                                      BlockState placedState, Item placedItem) {
        if (world instanceof ServerLevel serverLevel) {
            dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                    .ifPresent(runtime -> {
                        recordPlacement(runtime.auditService(), serverLevel, player, position, placedState, placedItem);
                    });
        }
    }

    public static BlockPos actualPlacementPosition(BlockPos contextClickedPos) {
        return contextClickedPos.immutable();
    }

    private static void recordPlacement(dev.chirana.umbrellaz.audit.AuditService auditService,
                                        ServerLevel level, Player player, BlockPos position,
                                        BlockState state, Item item) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
        String blockType = registryKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        String itemType = registryKey(BuiltInRegistries.ITEM.getKey(item));
        record(auditService, serverPlayer, AuditActions.BLOCK_PLACED, blockType, itemType,
                level, position, "placed");
    }

    private static void recordBreak(dev.chirana.umbrellaz.audit.AuditService auditService,
                                    ServerLevel level, Player player, BlockPos position, BlockState state) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
        String blockType = registryKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        String itemType = registryKey(BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()));
        record(auditService, serverPlayer, AuditActions.BLOCK_BROKEN, blockType, itemType,
                level, position, "broken");
    }

    private static void record(dev.chirana.umbrellaz.audit.AuditService auditService,
                               net.minecraft.server.level.ServerPlayer player, String action,
                               String blockType, String itemType, ServerLevel level,
                               BlockPos position, String reasonCode) {
        if (auditService == null) return;
        try {
            AuditPayload payload = AuditPayload.forAction(action,
                    AuditPayload.blockType(blockType), AuditPayload.itemType(itemType),
                    AuditPayload.world(level.dimension().identifier().toString()),
                    AuditPayload.position(position.getX(), position.getY(), position.getZ()),
                    AuditPayload.playerUuid(player.getUUID()), AuditPayload.playerName(player.getName().getString()));
            auditService.record(new AuditRecordRequest(
                    AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(), player.getName().getString())),
                    Source.EVENT, action, Outcome.SUCCESS,
                    new Target("block", blockTarget(level, position)), reasonCode, 1, payload,
                    AuditDelivery.BEST_EFFORT)).exceptionally(failure -> null);
        } catch (RuntimeException ignored) {
            // Audit delivery is best effort and must never alter world behavior.
        }
    }

    private static String blockTarget(ServerLevel level, BlockPos position) {
        return level.dimension().identifier() + ":" + position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static String registryKey(net.minecraft.resources.Identifier identifier) {
        return identifier.toString().toLowerCase(Locale.ROOT);
    }
}
