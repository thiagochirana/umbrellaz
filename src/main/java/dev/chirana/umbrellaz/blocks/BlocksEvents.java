package dev.chirana.umbrellaz.blocks;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class BlocksEvents {
    private static BlocksService blocksService;

    private BlocksEvents() {
    }

    public static void register(BlocksService blocksService) {
        BlocksEvents.blocksService = blocksService;
        PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) ->
                blocksService.afterSuccessfulBreak(world, player, position, state));
    }

    public static void afterSuccessfulPlayerPlacement(Level world, Player player, BlockPos position,
                                                      BlockState placedState) {
        BlocksService service = blocksService;
        if (service != null) {
            service.afterSuccessfulPlacement(world, player, position, placedState);
        }
    }
}
