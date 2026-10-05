package dev.chirana.umbrellaz.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExplosionAuditSummary {
    public static final int MAX_CANDIDATES = 128;
    public static final int MAX_GROUPS = 16;

    private ExplosionAuditSummary() {
    }

    public record Position(int x, int y, int z) {
    }

    public record BlockChange(String oldType, String newType) {
        public BlockChange {
            Objects.requireNonNull(oldType, "Old block type must not be null");
            Objects.requireNonNull(newType, "New block type must not be null");
        }
    }

    public record Group(String oldType, String newType, int count) {
        public Group {
            Objects.requireNonNull(oldType, "Old block type must not be null");
            Objects.requireNonNull(newType, "New block type must not be null");
            if (count <= 0) {
                throw new IllegalArgumentException("Explosion group count must be positive");
            }
        }
    }

    public record Summary(Position anchor, int changedCount, List<Group> groups) {
        public Summary {
            Objects.requireNonNull(anchor, "Explosion anchor must not be null");
            groups = List.copyOf(groups);
            if (changedCount <= 0 || changedCount > MAX_CANDIDATES) {
                throw new IllegalArgumentException("Explosion changed count is out of bounds");
            }
            if (groups.isEmpty() || groups.size() > MAX_GROUPS) {
                throw new IllegalArgumentException("Explosion group count is out of bounds");
            }
        }
    }

    public static Summary summarize(Position anchor, List<BlockChange> changes) {
        Objects.requireNonNull(anchor, "Explosion anchor must not be null");
        Objects.requireNonNull(changes, "Explosion changes must not be null");
        Map<Pair, Integer> grouped = new LinkedHashMap<>();
        int changedCount = 0;
        for (int index = 0; index < changes.size() && index < MAX_CANDIDATES; index++) {
            BlockChange change = Objects.requireNonNull(changes.get(index), "Explosion change must not be null");
            changedCount++;
            Pair pair = new Pair(change.oldType(), change.newType());
            if (grouped.containsKey(pair) || grouped.size() < MAX_GROUPS) {
                grouped.merge(pair, 1, Integer::sum);
            }
        }
        if (changedCount == 0 || grouped.isEmpty()) {
            return null;
        }
        List<Group> groups = new ArrayList<>(grouped.size());
        grouped.forEach((pair, count) -> groups.add(new Group(pair.oldType(), pair.newType(), count)));
        return new Summary(anchor, changedCount, groups);
    }

    private record Pair(String oldType, String newType) {
    }
}
