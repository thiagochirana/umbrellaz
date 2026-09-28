package dev.chirana.umbrellaz.lock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class MarkerReconciliationPlan {
    private MarkerReconciliationPlan() {
    }

    static Plan plan(Position expected, List<Position> actual) {
        Objects.requireNonNull(actual, "actual");
        int keeper = -1;
        if (expected != null) {
            for (int index = 0; index < actual.size(); index++) {
                if (expected.equals(actual.get(index))) {
                    keeper = index;
                    break;
                }
            }
        }

        List<Integer> discard = new ArrayList<>();
        for (int index = 0; index < actual.size(); index++) {
            if (index != keeper) {
                discard.add(index);
            }
        }
        return new Plan(keeper, List.copyOf(discard), expected != null && keeper < 0);
    }

    static List<Chunk> affectedTargetChunks(List<Position> targets) {
        Objects.requireNonNull(targets, "targets");
        return targets.stream()
                .map(position -> new Chunk(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16)))
                .distinct()
                .toList();
    }

    record Position(int x, int y, int z) {
    }

    record Chunk(int x, int z) {
    }

    record Plan(int keeperIndex, List<Integer> discardIndices, boolean create) {
        Plan {
            Objects.requireNonNull(discardIndices, "discardIndices");
            discardIndices = List.copyOf(discardIndices);
        }
    }
}
