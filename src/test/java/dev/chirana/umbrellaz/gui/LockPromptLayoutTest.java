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
        assertEquals(408, layout.panel().width());
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
        assertTrue(layout.panel().width() <= 328);
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
        assertEquals(new UiRect(6, 6, 228, 168), first.panel());
        assertEquals(new UiRect(20, 29, 200, 20), first.title());
        assertEquals(new UiRect(20, 64, 200, 36), first.input());
        assertEquals(new UiRect(20, 105, 200, 24), first.message());
        assertEquals(new UiRect(20, 142, 96, 24), first.cancel());
        assertEquals(new UiRect(124, 142, 96, 24), first.submit());
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
