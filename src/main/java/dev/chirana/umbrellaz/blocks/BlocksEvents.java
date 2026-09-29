package dev.chirana.umbrellaz.blocks;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class BlocksEvents {
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED = new java.util.concurrent.atomic.AtomicBoolean();

    private BlocksEvents() {
    }

    public static void installGlobalCallbacks() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) -> {
            if (world instanceof ServerLevel serverLevel) {
                dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                        .ifPresent(runtime -> runtime.blocksService().afterSuccessfulBreak(world, player, position, state));
            }
        });
    }

    public static void afterSuccessfulPlayerPlacement(Level world, Player player, BlockPos position,
                                                      BlockState placedState) {
        if (world instanceof ServerLevel serverLevel) {
            dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry.findReady(serverLevel.getServer())
                    .ifPresent(runtime -> runtime.blocksService().afterSuccessfulPlacement(world, player, position, placedState));
        }
    }
}
