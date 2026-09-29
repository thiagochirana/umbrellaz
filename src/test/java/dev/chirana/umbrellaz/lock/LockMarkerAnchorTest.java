package dev.chirana.umbrellaz.lock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LockMarkerAnchorTest {
    private static final double TOLERANCE = 0.000001D;

    @Test
    void anchorsEveryFacingAtItsFaceLocalUpperRightCorner() {
        assertAnchor(10.14D, 20.86D, 30.01D, 180.0F, 0.0F, 0, 0, -1);
        assertAnchor(10.86D, 20.86D, 30.99D, 0.0F, 0.0F, 0, 0, 1);
        assertAnchor(10.01D, 20.86D, 30.86D, 90.0F, 0.0F, -1, 0, 0);
        assertAnchor(10.99D, 20.86D, 30.14D, -90.0F, 0.0F, 1, 0, 0);
        assertAnchor(10.86D, 20.99D, 30.14D, 0.0F, -90.0F, 0, 1, 0);
        assertAnchor(10.86D, 20.01D, 30.86D, 0.0F, 90.0F, 0, -1, 0);
        assertEquals(0.18F, LockMarkerAnchor.SCALE);
    }

    @Test
    void scaledMarkerKeepsAVisibleMarginInsideTheFaceEdges() {
        LockMarkerAnchor.Anchor anchor = LockMarkerAnchor.frontFace(10, 20, 30, 0, 0, -1);
        double halfScale = LockMarkerAnchor.SCALE / 2.0D;

        assertEquals(10.05D, anchor.x() - halfScale, TOLERANCE);
        assertEquals(20.95D, anchor.y() + halfScale, TOLERANCE);
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
