package dev.chirana.umbrellaz.lock;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display.ItemDisplay;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class LockMarkerService {
    static final String MARKER_TAG = LockMarkerIdentity.MARKER_TAG;

    private final LockCache cache;

    LockMarkerService(LockCache cache) {
        this.cache = cache;
    }

    void reconcileAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            reconcileLoaded(level);
        }
    }

    void reconcileLoaded(ServerLevel level) {
        if (!cache.isReady() || !LockWorldIdentity.isReady(level)) {
            return;
        }
        Set<ChunkPos> chunks = new HashSet<>();
        for (LockCache.ChunkCoordinate coordinate : cache.chunkCoordinates(level)) {
            ChunkPos chunk = new ChunkPos(coordinate.x(), coordinate.z());
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                chunks.add(chunk);
            }
        }
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemDisplay display && isMarker(display)) {
                chunks.add(ChunkPos.containing(display.blockPosition()));
            }
        }
        for (ChunkPos chunk : chunks) {
            reconcileChunk(level, chunk);
        }
    }

    void reconcileChunk(ServerLevel level, ChunkPos chunk) {
        if (!cache.isReady() || !LockWorldIdentity.isReady(level)
                || level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
            return;
        }
        Map<UUID, ExpectedMarker> expected = expectedMarkers(level, chunk);
        List<ItemDisplay> displays = new ArrayList<>();
        AABB bounds = new AABB(chunk.getMinBlockX(), level.getMinY(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX() + 1.0D, level.getMaxY() + 1.0D, chunk.getMaxBlockZ() + 1.0D);
        level.getEntities(EntityTypeTest.forExactClass(ItemDisplay.class), bounds,
                display -> isMarker(display), displays);

        Map<UUID, List<ItemDisplay>> actual = new HashMap<>();
        for (ItemDisplay display : displays) {
            lockerId(display).ifPresent(id -> actual.computeIfAbsent(id, ignored -> new ArrayList<>()).add(display));
        }
        Set<UUID> identities = new HashSet<>(actual.keySet());
        identities.addAll(expected.keySet());
        for (UUID lockerId : identities) {
            List<ItemDisplay> candidates = actual.getOrDefault(lockerId, List.of());
            ExpectedMarker expectedMarker = expected.get(lockerId);
            List<MarkerReconciliationPlan.Position> actualPositions = candidates.stream()
                    .map(display -> position(display.blockPosition())).toList();
            MarkerReconciliationPlan.Plan plan = MarkerReconciliationPlan.plan(
                    expectedMarker == null ? null : position(expectedMarker.position()), actualPositions);
            for (int index : plan.discardIndices()) {
                candidates.get(index).discard();
            }
            if (plan.create()) {
                create(level, expectedMarker, lockerId);
            } else if (plan.keeperIndex() >= 0) {
                configure(candidates.get(plan.keeperIndex()), expectedMarker);
            }
        }
    }

    void reconcileAfterRemoval(ServerLevel level, Collection<PlacementProvenance> placements) {
        if (LockWorldIdentity.isReady(level)) {
            reconcileTargets(level, placements);
        }
    }

    void reconcileTargets(ServerLevel level, Collection<PlacementProvenance> placements) {
        List<MarkerReconciliationPlan.Position> targetPositions = placements.stream()
                .filter(placement -> targetInLevel(level, placement.target()))
                .map(placement -> new MarkerReconciliationPlan.Position(placement.target().position().x(),
                        placement.target().position().y(), placement.target().position().z()))
                .toList();
        MarkerReconciliationPlan.affectedTargetChunks(targetPositions).stream()
                .map(chunk -> new ChunkPos(chunk.x(), chunk.z()))
                .forEach(chunk -> reconcileChunk(level, chunk));
    }

    private Map<UUID, ExpectedMarker> expectedMarkers(ServerLevel level, ChunkPos chunk) {
        Map<UUID, ExpectedMarker> expected = new HashMap<>();
        for (LockerMetadata locker : cache.lockersInChunk(level, chunk)) {
            Optional<LockerMember> canonical = locker.members().stream()
                    .filter(member -> targetInLevel(level, member.target()))
                    .min(Comparator.comparingInt((LockerMember member) -> member.target().position().x())
                            .thenComparingInt(member -> member.target().position().y())
                            .thenComparingInt(member -> member.target().position().z()));
            if (canonical.isEmpty()) {
                continue;
            }
            LockerMember member = canonical.get();
            BlockPos position = toBlockPos(member.target());
            if (!ChunkPos.containing(position).equals(chunk)) {
                continue;
            }
            BlockState state = level.getBlockState(position);
            if (LockBlockAdapter.classify(state)
                    .filter(member.blockType()::equals).isEmpty()) {
                continue;
            }
            Optional<Direction> facing = LockBlockAdapter.frontFacing(state);
            if (facing.isEmpty()) {
                continue;
            }
            expected.put(locker.lockerId(), new ExpectedMarker(position, facing.get()));
        }
        return expected;
    }

    private boolean targetInLevel(ServerLevel level, LockTarget target) {
        return LockWorldIdentity.tryTarget(level, toBlockPos(target))
                .map(current -> current.world().equals(target.world()) && current.dimension().equals(target.dimension()))
                .orElse(false);
    }

    private void create(ServerLevel level, ExpectedMarker marker, UUID lockerId) {
        ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }
        configure(display, marker);
        display.addTag(MARKER_TAG);
        display.addTag(LockMarkerIdentity.lockerTag(lockerId));
        level.addFreshEntity(display);
    }

    private void configure(ItemDisplay display, ExpectedMarker marker) {
        BlockPos position = marker.position();
        Direction facing = marker.facing();
        LockMarkerAnchor.Anchor anchor = LockMarkerAnchor.frontFace(position.getX(), position.getY(), position.getZ(),
                facing.getStepX(), facing.getStepY(), facing.getStepZ());
        display.setPos(anchor.x(), anchor.y(), anchor.z());
        display.setYRot(anchor.yaw());
        display.setXRot(anchor.pitch());
        display.setTransformation(new Transformation(null, null,
                new Vector3f(LockMarkerAnchor.SCALE), null));
        display.setNoGravity(true);
        display.setSilent(true);
        display.getSlot(0).set(LockItem.create());
    }

    private boolean isMarker(ItemDisplay display) {
        return LockMarkerIdentity.isMarker(display.entityTags());
    }

    private MarkerReconciliationPlan.Position position(BlockPos position) {
        return new MarkerReconciliationPlan.Position(position.getX(), position.getY(), position.getZ());
    }

    private Optional<UUID> lockerId(ItemDisplay display) {
        return LockMarkerIdentity.lockerId(display.entityTags());
    }

    private BlockPos toBlockPos(LockerMember member) {
        return toBlockPos(member.target());
    }

    private BlockPos toBlockPos(LockTarget target) {
        return new BlockPos(target.position().x(), target.position().y(), target.position().z());
    }

    private record ExpectedMarker(BlockPos position, Direction facing) {
    }
}
