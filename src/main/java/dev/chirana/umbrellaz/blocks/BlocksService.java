package dev.chirana.umbrellaz.blocks;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
    private final Set<UUID> processingPlayers = new HashSet<>();

    public BlocksService() {
        this(new BlocksRuntimeState());
    }

    public BlocksService(BlocksRuntimeState runtimeState) {
        this.runtimeState = runtimeState;
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

    public void afterSuccessfulBreak(Level world, Player player, BlockPos position, BlockState brokenState) {
        if (!(world instanceof ServerLevel serverWorld) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        boolean shiftHeld = serverPlayer.isShiftKeyDown();
        UUID playerUuid = serverPlayer.getUUID();
        if (!processingPlayers.add(playerUuid)) {
            return;
        }
        try {
            Block initialBlock = brokenState.getBlock();
            if (shouldCascadeTreeBreak(treeEzBreakEnabled(), shiftHeld, isTreeLog(brokenState))) {
                breakConnected(serverWorld, serverPlayer, position,
                        state -> isTreeLog(state) && sameBlock(state.getBlock(), initialBlock));
            } else if (shouldCascadeOreBreak(oresEzBreakEnabled(), shiftHeld, isOre(brokenState))) {
                breakConnected(serverWorld, serverPlayer, position,
                        state -> isOre(state) && sameBlock(state.getBlock(), initialBlock));
            }
        } finally {
            processingPlayers.remove(playerUuid);
        }
    }

    static boolean shouldCascadeTreeBreak(boolean enabled, boolean shiftHeld, boolean isTreeLog) {
        return enabled && shiftHeld && isTreeLog;
    }

    static boolean shouldCascadeOreBreak(boolean enabled, boolean shiftHeld, boolean isOre) {
        return enabled && shiftHeld && isOre;
    }

    static boolean isTreeLog(BlockState state) {
        return state.is(BlockTags.LOGS)
                && isRawTreeLogIdentifier(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    static boolean isRawTreeLogIdentifier(Identifier identifier) {
        String path = identifier.getPath();
        return !path.startsWith("stripped_")
                && !path.endsWith("_stem")
                && !path.endsWith("_hyphae");
    }

    static boolean isOre(BlockState state) {
        return VANILLA_ORE_TAGS.stream().anyMatch(state::is);
    }

    static boolean sameBlock(Object candidate, Object initialBlock) {
        return candidate == initialBlock;
    }

    private void breakConnected(ServerLevel world, ServerPlayer player, BlockPos origin,
                                Predicate<BlockState> accepted) {
        List<BlockPos> positions = connectedPositions(world, origin, accepted);
        for (BlockPos position : positions) {
            if (accepted.test(world.getBlockState(position))) {
                player.gameMode.destroyBlock(position);
            }
        }
    }

    private List<BlockPos> connectedPositions(ServerLevel world, BlockPos origin,
                                              Predicate<BlockState> accepted) {
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
