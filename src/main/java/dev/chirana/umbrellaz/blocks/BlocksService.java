package dev.chirana.umbrellaz.blocks;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class BlocksService {
    private static final TagKey<Block> MINECRAFT_ORES = TagKey.of(RegistryKeys.BLOCK, Identifier.ofVanilla("ores"));
    private static final Set<TagKey<Block>> VANILLA_ORE_TAGS = Set.of(
            MINECRAFT_ORES,
            BlockTags.COAL_ORES,
            BlockTags.COPPER_ORES,
            BlockTags.DIAMOND_ORES,
            BlockTags.EMERALD_ORES,
            BlockTags.GOLD_ORES,
            BlockTags.IRON_ORES,
            BlockTags.LAPIS_ORES,
            BlockTags.REDSTONE_ORES
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

    public void afterSuccessfulBreak(World world, PlayerEntity player, BlockPos position, BlockState brokenState) {
        if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        UUID playerUuid = serverPlayer.getUuid();
        if (!processingPlayers.add(playerUuid)) {
            return;
        }
        try {
            if (treeEzBreakEnabled() && isTreeLog(brokenState)) {
                breakConnected(serverWorld, serverPlayer, position, state -> state.isIn(BlockTags.LOGS));
            } else if (oresEzBreakEnabled() && isOre(brokenState)) {
                Block oreType = brokenState.getBlock();
                breakConnected(serverWorld, serverPlayer, position,
                        state -> isOre(state) && state.getBlock() == oreType);
            }
        } finally {
            processingPlayers.remove(playerUuid);
        }
    }

    static boolean isTreeLog(BlockState state) {
        return state.isIn(BlockTags.LOGS);
    }

    static boolean isOre(BlockState state) {
        return VANILLA_ORE_TAGS.stream().anyMatch(state::isIn);
    }

    private void breakConnected(ServerWorld world, ServerPlayerEntity player, BlockPos origin,
                                Predicate<BlockState> accepted) {
        List<BlockPos> positions = connectedPositions(world, origin, accepted);
        for (BlockPos position : positions) {
            if (accepted.test(world.getBlockState(position))) {
                player.interactionManager.tryBreakBlock(position);
            }
        }
    }

    private List<BlockPos> connectedPositions(ServerWorld world, BlockPos origin,
                                              Predicate<BlockState> accepted) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> positions = new java.util.ArrayList<>();
        visited.add(origin.toImmutable());
        for (Direction direction : Direction.values()) {
            queue.add(origin.offset(direction));
        }
        while (!queue.isEmpty()) {
            BlockPos position = queue.removeFirst().toImmutable();
            if (!visited.add(position)) {
                continue;
            }
            BlockState state = world.getBlockState(position);
            if (!accepted.test(state)) {
                continue;
            }
            positions.add(position);
            for (Direction direction : Direction.values()) {
                queue.add(position.offset(direction));
            }
        }
        return positions;
    }
}
