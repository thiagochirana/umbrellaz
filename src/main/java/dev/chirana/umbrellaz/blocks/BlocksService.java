package dev.chirana.umbrellaz.blocks;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class BlocksService {
    private static final Set<TagKey<Block>> VANILLA_ORE_TAGS = Set.of(
            BlockTags.ORES,
            BlockTags.COPPER_ORES,
            BlockTags.GOLD_ORES,
            BlockTags.IRON_ORES
    );

    private final BlocksRuntimeState runtimeState;
    private final PlacedLogTracker placedLogTracker;
    private final Set<UUID> processingPlayers = new HashSet<>();

    public BlocksService() {
        this(new BlocksRuntimeState(), new PlacedLogTracker());
    }

    public BlocksService(BlocksRuntimeState runtimeState) {
        this(runtimeState, new PlacedLogTracker());
    }

    public BlocksService(BlocksRuntimeState runtimeState, PlacedLogTracker placedLogTracker) {
        this.runtimeState = runtimeState;
        this.placedLogTracker = placedLogTracker;
    }

    public boolean treeEzBreakEnabled() {
        return runtimeState.treeEzBreakEnabled();
    }

    public void setTreeEzBreakEnabled(boolean enabled) {
        runtimeState.setTreeEzBreakEnabled(enabled);
    }

    public boolean oresEzBreakEnabled() {
        return runtimeState.oresEzBreakEnabled();
    }

    public void setOresEzBreakEnabled(boolean enabled) {
        runtimeState.setOresEzBreakEnabled(enabled);
    }

    public void afterSuccessfulPlacement(Level world, Player player, BlockPos position, BlockState placedState) {
        if (!(world instanceof ServerLevel serverWorld) || !(player instanceof ServerPlayer)) {
            return;
        }
        placedLogTracker.record(serverWorld.dimension(), position, isTreeLog(placedState));
    }

    public void afterSuccessfulBreak(Level world, Player player, BlockPos position, BlockState brokenState) {
        if (!(world instanceof ServerLevel serverWorld) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (placedLogTracker.remove(serverWorld.dimension(), position)) {
            return;
        }
        UUID playerUuid = serverPlayer.getUUID();
        if (!processingPlayers.add(playerUuid)) {
            return;
        }
        try {
            if (treeEzBreakEnabled() && isTreeLog(brokenState)) {
                breakConnected(serverWorld, serverPlayer, position, state -> state.is(BlockTags.LOGS), true);
            } else if (oresEzBreakEnabled() && isOre(brokenState)) {
                Block oreType = brokenState.getBlock();
                breakConnected(serverWorld, serverPlayer, position,
                        state -> isOre(state) && state.getBlock() == oreType, false);
            }
        } finally {
            processingPlayers.remove(playerUuid);
        }
    }

    static boolean isTreeLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    static boolean isOre(BlockState state) {
        return VANILLA_ORE_TAGS.stream().anyMatch(state::is);
    }

    private void breakConnected(ServerLevel world, ServerPlayer player, BlockPos origin,
                                Predicate<BlockState> accepted, boolean excludePlacedLogs) {
        List<BlockPos> positions = connectedPositions(world, origin, accepted, excludePlacedLogs);
        for (BlockPos position : positions) {
            if (accepted.test(world.getBlockState(position))) {
                player.gameMode.destroyBlock(position);
            }
        }
    }

    private List<BlockPos> connectedPositions(ServerLevel world, BlockPos origin,
                                              Predicate<BlockState> accepted, boolean excludePlacedLogs) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> positions = new java.util.ArrayList<>();
        visited.add(origin.immutable());
        for (Direction direction : Direction.values()) {
            queue.add(origin.relative(direction));
        }
        while (!queue.isEmpty()) {
            BlockPos position = queue.removeFirst().immutable();
            if (!visited.add(position)) {
                continue;
            }
            BlockState state = world.getBlockState(position);
            if (excludePlacedLogs && placedLogTracker.shouldSkipTreeTraversal(
                    world.dimension(), position, isTreeLog(state))) {
                continue;
            }
            if (!accepted.test(state)) {
                continue;
            }
            positions.add(position);
            for (Direction direction : Direction.values()) {
                queue.add(position.relative(direction));
            }
        }
        return positions;
    }
}
