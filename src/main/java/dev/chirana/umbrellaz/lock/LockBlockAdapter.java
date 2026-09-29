package dev.chirana.umbrellaz.lock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Optional;

final class LockBlockAdapter {
    private LockBlockAdapter() {
    }

    static Optional<LockBlockType> classify(BlockState state) {
        if (state == null) {
            return Optional.empty();
        }
        if (state.getBlock() == Blocks.CHEST) {
            return Optional.of(LockBlockType.CHEST);
        }
        if (state.getBlock() == Blocks.TRAPPED_CHEST) {
            return Optional.of(LockBlockType.TRAPPED_CHEST);
        }
        if (state.getBlock() == Blocks.BARREL) {
            return Optional.of(LockBlockType.BARREL);
        }
        return Optional.empty();
    }

    static Optional<Direction> frontFacing(BlockState state) {
        if (state == null) {
            return Optional.empty();
        }
        if ((state.getBlock() == Blocks.CHEST || state.getBlock() == Blocks.TRAPPED_CHEST)
                && state.hasProperty(ChestBlock.FACING)) {
            return Optional.of(state.getValue(ChestBlock.FACING));
        }
        if (state.getBlock() == Blocks.BARREL && state.hasProperty(BarrelBlock.FACING)) {
            return Optional.of(state.getValue(BarrelBlock.FACING));
        }
        return Optional.empty();
    }

    static BlockPos connectedChestPosition(Level level, BlockPos position, BlockState state) {
        if (state.getBlock() == Blocks.CHEST || state.getBlock() == Blocks.TRAPPED_CHEST) {
            if (!state.hasProperty(ChestBlock.TYPE)
                    || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                return position;
            }
            return ChestBlock.getConnectedBlockPos(position, state);
        }
        return position;
    }
}
