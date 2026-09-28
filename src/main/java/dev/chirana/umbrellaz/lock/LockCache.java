package dev.chirana.umbrellaz.lock;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class LockCache {
    private volatile Snapshot snapshot = new Snapshot(false, Map.of());

    public boolean isReady() {
        return snapshot.ready();
    }

    public void replaceSnapshot(Collection<LockerMetadata> lockers) {
        Objects.requireNonNull(lockers, "lockers");
        Map<LockTarget, LockerMetadata> resolved = new HashMap<>();
        for (LockerMetadata locker : lockers) {
            for (LockerMember member : locker.members()) {
                if (resolved.putIfAbsent(member.target(), locker) != null) {
                    throw new IllegalArgumentException("A target belongs to more than one locker: " + member.target());
                }
            }
        }
        snapshot = new Snapshot(true, Map.copyOf(resolved));
    }

    public void markNotReady() {
        snapshot = new Snapshot(false, Map.of());
    }

    public void removeLocker(UUID lockerId) {
        Objects.requireNonNull(lockerId, "lockerId");
        Snapshot current = snapshot;
        if (!current.ready()) {
            return;
        }
        Map<LockTarget, LockerMetadata> replacement = new HashMap<>(current.lockers());
        replacement.entrySet().removeIf(entry -> entry.getValue().lockerId().equals(lockerId));
        snapshot = new Snapshot(true, replacement);
    }

    public void removeLockerAt(LockTarget target) {
        Objects.requireNonNull(target, "target");
        Snapshot current = snapshot;
        LockerMetadata locker = current.lockers().get(target);
        if (locker != null) {
            removeLocker(locker.lockerId());
        }
    }

    public void removeLockerAtIfNotGeneration(LockTarget target, UUID generationId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(generationId, "generationId");
        Snapshot current = snapshot;
        LockerMetadata locker = current.lockers().get(target);
        if (locker != null && locker.members().stream().noneMatch(member ->
                member.target().equals(target) && member.generationId().equals(generationId))) {
            removeLocker(locker.lockerId());
        }
    }

    public void removeLockerAtGeneration(LockTarget target, UUID generationId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(generationId, "generationId");
        Snapshot current = snapshot;
        LockerMetadata locker = current.lockers().get(target);
        if (locker != null && locker.members().stream().anyMatch(member ->
                member.target().equals(target) && member.generationId().equals(generationId))) {
            removeLocker(locker.lockerId());
        }
    }

    public void addLocker(LockerMetadata locker) {
        Objects.requireNonNull(locker, "locker");
        Snapshot current = snapshot;
        if (!current.ready()) {
            throw new IllegalStateException("Lock cache is not ready");
        }
        Map<LockTarget, LockerMetadata> replacement = new HashMap<>(current.lockers());
        for (LockerMember member : locker.members()) {
            LockerMetadata existing = replacement.putIfAbsent(member.target(), locker);
            if (existing != null && !existing.lockerId().equals(locker.lockerId())) {
                throw new IllegalArgumentException("A target belongs to more than one locker: " + member.target());
            }
        }
        snapshot = new Snapshot(true, replacement);
    }

    public Optional<LockerMetadata> resolve(PlacementProvenance placement) {
        Lookup lookup = lookup(placement);
        return lookup.locker() == null ? Optional.empty() : Optional.of(lookup.locker());
    }

    public Optional<PlacementProvenance> provenance(LockTarget target) {
        Snapshot current = snapshot;
        LockerMetadata locker = current.lockers().get(target);
        if (!current.ready() || locker == null) {
            return Optional.empty();
        }
        return locker.members().stream()
                .filter(member -> member.target().equals(target))
                .map(member -> new PlacementProvenance(member.target(), member.generationId(),
                        member.placedBy(), member.blockType(), locker.updatedAt()))
                .findFirst();
    }

    public Lookup lookup(PlacementProvenance placement) {
        Objects.requireNonNull(placement, "placement");
        Snapshot current = snapshot;
        LockerMetadata locker = current.lockers().get(placement.target());
        if (locker != null && locker.members().stream().noneMatch(member ->
                member.generationId().equals(placement.generationId())
                        && member.placedBy().equals(placement.placedBy())
                        && member.blockType() == placement.blockType())) {
            locker = null;
        }
        return new Lookup(current.ready(), locker);
    }

    List<LockerMetadata> lockers() {
        Snapshot current = snapshot;
        return current.lockers().values().stream().distinct().toList();
    }

    List<LockerMetadata> lockersInChunk(ServerLevel level, ChunkPos chunk) {
        if (!snapshot.ready()) {
            return List.of();
        }
        return LockWorldIdentity.tryTarget(level, chunk.getWorldPosition())
                .map(target -> snapshot.byChunk().getOrDefault(new ChunkCoordinate(
                        target.world().value(), target.dimension().value(), chunk.x(), chunk.z()), Set.of()))
                .orElse(Set.of()).stream().toList();
    }

    Set<ChunkCoordinate> chunkCoordinates(ServerLevel level) {
        if (!snapshot.ready()) {
            return Set.of();
        }
        String world = LockWorldIdentity.tryTarget(level, ChunkPos.ZERO.getWorldPosition())
                .map(target -> target.world().value()).orElse(null);
        String dimension = level.dimension().identifier().toString();
        return snapshot.byChunk().keySet().stream()
                .filter(coordinate -> coordinate.world().equals(world) && coordinate.dimension().equals(dimension))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record Snapshot(boolean ready, Map<LockTarget, LockerMetadata> lockers,
                            Map<ChunkCoordinate, Set<LockerMetadata>> byChunk) {
        private Snapshot(boolean ready, Map<LockTarget, LockerMetadata> lockers) {
            this(ready, lockers, index(lockers));
        }

        private Snapshot {
            lockers = Collections.unmodifiableMap(new HashMap<>(lockers));
            Map<ChunkCoordinate, Set<LockerMetadata>> indexed = new HashMap<>();
            byChunk.forEach((coordinate, values) -> indexed.put(coordinate,
                    Collections.unmodifiableSet(new java.util.LinkedHashSet<>(values))));
            byChunk = Collections.unmodifiableMap(indexed);
        }

        private static Map<ChunkCoordinate, Set<LockerMetadata>> index(Map<LockTarget, LockerMetadata> lockers) {
            Map<ChunkCoordinate, Set<LockerMetadata>> indexed = new HashMap<>();
            lockers.forEach((target, locker) -> indexed.computeIfAbsent(new ChunkCoordinate(
                    target.world().value(), target.dimension().value(),
                    target.position().x() >> 4, target.position().z() >> 4),
                    ignored -> new java.util.LinkedHashSet<>()).add(locker));
            return indexed;
        }
    }

    record ChunkCoordinate(String world, String dimension, int x, int z) {
    }

    public record Lookup(boolean ready, LockerMetadata locker) {
        public boolean locked() {
            return locker != null;
        }
    }
}
