package dev.chirana.umbrellaz.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockPromptLayoutTest {
    @Test
    void selectsStandardLayoutAndClampsPanelWidth() {
        LockPromptLayout layout = LockPromptLayout.calculate(960, 540);

        assertEquals(LockPromptLayout.Density.STANDARD, layout.density());
        assertEquals(new UiRect(320, 175, 320, 190), layout.panel());
        assertEquals(new UiRect(340, 193, 280, 12), layout.title());
        assertEquals(new UiRect(340, 227, 280, 36), layout.input());
        assertEquals(new UiRect(340, 270, 280, 22), layout.message());
        assertEquals(new UiRect(340, 327, 136, 26), layout.cancel());
        assertEquals(new UiRect(484, 327, 136, 26), layout.submit());
        assertFalse(layout.belowMinimum());
        assertContained(layout.panel(), layout.input());
        assertContained(layout.panel(), layout.submit());
        assertContained(layout.panel(), layout.cancel());
    }

    @Test
    void selectsCompactLayoutAtSupportedSmallSize() {
        LockPromptLayout layout = LockPromptLayout.calculate(320, 240);

        assertEquals(LockPromptLayout.Density.COMPACT, layout.density());
        assertFalse(layout.belowMinimum());
        assertEquals(new UiRect(20, 38, 280, 164), layout.panel());
        assertTrue(layout.panel().width() <= 280);
        assertContained(layout.panel(), layout.message());
    }

    @Test
    void exactMinimumViewportProducesDeterministicSupportedGeometry() {
        LockPromptLayout first = LockPromptLayout.calculate(240, 180);
        LockPromptLayout second = LockPromptLayout.calculate(240, 180);

        assertEquals(first, second);
        assertEquals(240, first.viewportWidth());
        assertEquals(180, first.viewportHeight());
        assertEquals(LockPromptLayout.Density.COMPACT, first.density());
        assertFalse(first.belowMinimum());
        assertEquals(new UiRect(6, 8, 228, 164), first.panel());
        assertEquals(new UiRect(18, 23, 204, 12), first.title());
        assertEquals(new UiRect(18, 55, 204, 34), first.input());
        assertEquals(new UiRect(18, 94, 204, 18), first.message());
        assertEquals(new UiRect(18, 141, 98, 23), first.cancel());
        assertEquals(new UiRect(124, 141, 98, 23), first.submit());
        assertTrue(first.isTightCompact());
        assertSupportedGeometry(first);
    }

    @Test
    void onePixelBelowMinimumWidthUsesFallbackAtExactMinimumHeight() {
        LockPromptLayout layout = LockPromptLayout.calculate(239, 180);

        assertEquals(239, layout.viewportWidth());
        assertEquals(180, layout.viewportHeight());
        assertTrue(layout.belowMinimum());
        assertEquals(LockPromptLayout.Density.COMPACT, layout.density());
        assertEquals(new UiRect(4, 4, 231, 172), layout.panel());
        assertFallbackGeometry(layout);
    }

    @Test
    void onePixelBelowMinimumHeightUsesFallbackAtExactMinimumWidth() {
        LockPromptLayout layout = LockPromptLayout.calculate(240, 179);

        assertEquals(240, layout.viewportWidth());
        assertEquals(179, layout.viewportHeight());
        assertTrue(layout.belowMinimum());
        assertEquals(LockPromptLayout.Density.COMPACT, layout.density());
        assertEquals(new UiRect(4, 4, 232, 171), layout.panel());
        assertFallbackGeometry(layout);
    }

    @Test
    void shrinkAndRestoreRecomputesTheSameSupportedLayout() {
        LockPromptLayout initial = LockPromptLayout.calculate(320, 240);
        LockPromptLayout shrunk = LockPromptLayout.calculate(239, 179);
        LockPromptLayout restored = LockPromptLayout.calculate(320, 240);

        assertFalse(initial.belowMinimum());
        assertSupportedGeometry(initial);
        assertTrue(shrunk.belowMinimum());
        assertFallbackGeometry(shrunk);
        assertEquals(initial, restored);
        assertFalse(restored.belowMinimum());
        assertSupportedGeometry(restored);
    }

    @Test
    void marksBelowMinimumAndKeepsFallbackPanelInsideViewport() {
        LockPromptLayout layout = LockPromptLayout.calculate(180, 100);

        assertTrue(layout.belowMinimum());
        assertEquals(LockPromptLayout.Density.COMPACT, layout.density());
        assertContained(new UiRect(0, 0, 180, 100), layout.panel());
        assertEquals(0, layout.input().width());
    }

    private static void assertContained(UiRect outer, UiRect inner) {
        assertTrue(inner.x() >= outer.x());
        assertTrue(inner.y() >= outer.y());
        assertTrue(inner.right() <= outer.right());
        assertTrue(inner.bottom() <= outer.bottom());
    }

    private static void assertSupportedGeometry(LockPromptLayout layout) {
        assertContained(layout.panel(), layout.title());
        assertContained(layout.panel(), layout.input());
        assertContained(layout.panel(), layout.message());
        assertContained(layout.panel(), layout.cancel());
        assertContained(layout.panel(), layout.submit());
    }

    private static void assertFallbackGeometry(LockPromptLayout layout) {
        assertContained(new UiRect(0, 0, layout.viewportWidth(), layout.viewportHeight()), layout.panel());
        assertEquals(0, layout.title().width());
        assertEquals(0, layout.input().width());
        assertEquals(0, layout.message().width());
        assertEquals(0, layout.cancel().width());
        assertEquals(0, layout.submit().width());
        assertEquals(layout.panel().x(), layout.input().x());
        assertEquals(layout.panel().y(), layout.input().y());
    }
}
