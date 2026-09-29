package dev.chirana.umbrellaz.lock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LockMarkerAnchorTest {
    private static final double TOLERANCE = 0.000001D;

    @Test
    void anchorsEveryFacingJustInsideItsFrontFace() {
        assertAnchor(10.5D, 20.5D, 30.01D, 180.0F, 0.0F, 0, 0, -1);
        assertAnchor(10.5D, 20.5D, 30.99D, 0.0F, 0.0F, 0, 0, 1);
        assertAnchor(10.01D, 20.5D, 30.5D, 90.0F, 0.0F, -1, 0, 0);
        assertAnchor(10.99D, 20.5D, 30.5D, -90.0F, 0.0F, 1, 0, 0);
        assertAnchor(10.5D, 20.99D, 30.5D, 0.0F, -90.0F, 0, 1, 0);
        assertAnchor(10.5D, 20.01D, 30.5D, 0.0F, 90.0F, 0, -1, 0);
    }

    @Test
    void everyFrontAnchorKeepsTheStorageBlockPosition() {
        List<int[]> facings = List.of(
                new int[]{0, 0, -1}, new int[]{0, 0, 1},
                new int[]{-1, 0, 0}, new int[]{1, 0, 0},
                new int[]{0, 1, 0}, new int[]{0, -1, 0});

        for (int[] facing : facings) {
            LockMarkerAnchor.Anchor anchor = LockMarkerAnchor.frontFace(-11, -21, -31,
                    facing[0], facing[1], facing[2]);
            assertEquals(-11, (int) Math.floor(anchor.x()));
            assertEquals(-21, (int) Math.floor(anchor.y()));
            assertEquals(-31, (int) Math.floor(anchor.z()));
        }
    }

    @Test
    void rejectsNonCardinalFacingVectors() {
        assertThrows(IllegalArgumentException.class,
                () -> LockMarkerAnchor.frontFace(0, 0, 0, 1, 0, 1));
    }

    private void assertAnchor(double x, double y, double z, float yaw, float pitch,
                              int stepX, int stepY, int stepZ) {
        LockMarkerAnchor.Anchor anchor = LockMarkerAnchor.frontFace(10, 20, 30, stepX, stepY, stepZ);
        assertEquals(x, anchor.x(), TOLERANCE);
        assertEquals(y, anchor.y(), TOLERANCE);
        assertEquals(z, anchor.z(), TOLERANCE);
        assertEquals(yaw, anchor.yaw());
        assertEquals(pitch, anchor.pitch());
    }
}
