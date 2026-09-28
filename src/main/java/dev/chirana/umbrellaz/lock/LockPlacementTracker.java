package dev.chirana.umbrellaz.lock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LockPlacementTracker {
    private final Map<LockTarget, PlacementProvenance> placements = new ConcurrentHashMap<>();
    private final Set<LockTarget> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    void markNotReady() {
        ready = false;
        placements.clear();
        pending.clear();
    }

    void replace(Iterable<PlacementProvenance> snapshot) {
        Map<LockTarget, PlacementProvenance> replacement = new ConcurrentHashMap<>();
        snapshot.forEach(placement -> replacement.put(placement.target(), placement));
        placements.clear();
        placements.putAll(replacement);
        pending.clear();
        ready = true;
    }

    boolean isReady() {
        return ready;
    }

    PlacementProvenance record(ServerLevel level, BlockPos position, UUID installer, LockBlockType blockType) {
        LockTarget target = LockWorldIdentity.tryTarget(level, position)
                .orElseThrow(() -> new IllegalStateException("Lock world identity is not initialized"));
        PlacementProvenance provenance = new PlacementProvenance(
                target, UUID.randomUUID(), installer, blockType, Instant.now());
        placements.put(provenance.target(), provenance);
        return provenance;
    }

    Optional<PlacementProvenance> find(ServerLevel level, BlockPos position) {
        if (!ready) {
            return Optional.empty();
        }
        return LockWorldIdentity.tryTarget(level, position)
                .flatMap(this::find);
    }

    Optional<PlacementProvenance> find(LockTarget target) {
        return ready && !pending.contains(target) ? Optional.ofNullable(placements.get(target)) : Optional.empty();
    }

    Optional<PlacementProvenance> findForMutation(LockTarget target) {
        return ready ? Optional.ofNullable(placements.get(target)) : Optional.empty();
    }

    Optional<PlacementProvenance> findForMutation(ServerLevel level, BlockPos position) {
        return LockWorldIdentity.tryTarget(level, position).flatMap(this::findForMutation);
    }

    void markPending(PlacementProvenance placement) {
        pending.add(placement.target());
    }

    void markPersisted(PlacementProvenance placement) {
        pending.remove(placement.target());
    }

    void remove(PlacementProvenance provenance) {
        pending.remove(provenance.target());
        placements.remove(provenance.target(), provenance);
    }

    void remove(ServerLevel level, BlockPos position) {
        LockWorldIdentity.tryTarget(level, position).ifPresent(placements::remove);
    }

    boolean matches(ServerLevel level, BlockPos position, PlacementProvenance expected) {
        return find(level, position).map(expected::equals).orElse(false);
    }
}
