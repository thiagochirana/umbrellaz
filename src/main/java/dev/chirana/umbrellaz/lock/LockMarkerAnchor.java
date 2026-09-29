package dev.chirana.umbrellaz.lock;

final class LockMarkerAnchor {
    private static final double FACE_OFFSET = 0.49D;
    private static final double CORNER_OFFSET = 0.36D;
    static final float SCALE = 0.18F;

    private LockMarkerAnchor() {
    }

    static Anchor frontFace(int blockX, int blockY, int blockZ, int stepX, int stepY, int stepZ) {
        if (Math.abs(stepX) + Math.abs(stepY) + Math.abs(stepZ) != 1) {
            throw new IllegalArgumentException("A marker facing must be one cardinal direction");
        }
        int upX = 0;
        int upY = stepY == 0 ? 1 : 0;
        int upZ = stepY > 0 ? -1 : stepY < 0 ? 1 : 0;
        int rightX = upY * stepZ - upZ * stepY;
        int rightY = upZ * stepX - upX * stepZ;
        int rightZ = upX * stepY - upY * stepX;
        float yaw = stepY != 0 || stepZ > 0 ? 0.0F
                : stepX < 0 ? 90.0F
                : stepZ < 0 ? 180.0F
                : -90.0F;
        float pitch = stepY > 0 ? -90.0F : stepY < 0 ? 90.0F : 0.0F;
        return new Anchor(blockX + 0.5D + stepX * FACE_OFFSET + (rightX + upX) * CORNER_OFFSET,
                blockY + 0.5D + stepY * FACE_OFFSET + (rightY + upY) * CORNER_OFFSET,
                blockZ + 0.5D + stepZ * FACE_OFFSET + (rightZ + upZ) * CORNER_OFFSET, yaw, pitch);
    }

    record Anchor(double x, double y, double z, float yaw, float pitch) {
    }
}
