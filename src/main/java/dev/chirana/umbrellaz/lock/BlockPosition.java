package dev.chirana.umbrellaz.lock;

public record BlockPosition(int x, int y, int z) {
    public boolean isAdjacentTo(BlockPosition other) {
        return isHorizontallyAdjacentTo(other);
    }

    public boolean isHorizontallyAdjacentTo(BlockPosition other) {
        return y == other.y && Math.abs((long) x - other.x) + Math.abs((long) z - other.z) == 1;
    }
}
