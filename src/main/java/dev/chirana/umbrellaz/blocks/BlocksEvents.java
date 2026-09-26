package dev.chirana.umbrellaz.blocks;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

public final class BlocksEvents {
    private BlocksEvents() {
    }

    public static void register(BlocksService blocksService) {
        PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) ->
                blocksService.afterSuccessfulBreak(world, player, position, state));
    }
}
