package dev.chirana.umbrellaz.gui;

public record LockPromptLayout(
        int viewportWidth,
        int viewportHeight,
        Density density,
        boolean belowMinimum,
        UiRect panel,
        UiRect title,
        UiRect input,
        UiRect submit,
        UiRect cancel,
        UiRect message
) {
    public static final int MINIMUM_WIDTH = 240;
    public static final int MINIMUM_HEIGHT = 180;

    public static LockPromptLayout calculate(int viewportWidth, int viewportHeight) {
        int safeWidth = Math.max(0, viewportWidth);
        int safeHeight = Math.max(0, viewportHeight);
        boolean belowMinimum = safeWidth < MINIMUM_WIDTH || safeHeight < MINIMUM_HEIGHT;
        Density density = !belowMinimum && safeWidth >= 480 && safeHeight >= 340
                ? Density.STANDARD
                : Density.COMPACT;

        if (belowMinimum) {
            UiRect panel = insetViewport(safeWidth, safeHeight, 4);
            UiRect empty = new UiRect(panel.x(), panel.y(), 0, 0);
            return new LockPromptLayout(safeWidth, safeHeight, density, true, panel,
                    empty, empty, empty, empty, empty);
        }

        if (density == Density.STANDARD) {
            return standard(safeWidth, safeHeight);
        }
        return compact(safeWidth, safeHeight);
    }

    private static LockPromptLayout standard(int width, int height) {
        int panelWidth = Math.min(408, width - 32);
        int panelHeight = Math.min(284, height - 24);
        UiRect panel = centered(width, height, panelWidth, panelHeight);
        int contentX = panel.x() + 32;
        int contentWidth = panel.width() - 64;
        UiRect title = new UiRect(contentX, panel.y() + 48, contentWidth, 24);
        UiRect input = new UiRect(contentX, panel.y() + 121, contentWidth, 46);
        UiRect message = new UiRect(contentX, panel.y() + 178, contentWidth, 30);
        int buttonY = panel.bottom() - 48;
        int buttonGap = 10;
        int buttonWidth = (contentWidth - buttonGap) / 2;
        UiRect cancel = new UiRect(contentX, buttonY, buttonWidth, 32);
        UiRect submit = new UiRect(cancel.right() + buttonGap, buttonY,
                contentWidth - buttonWidth - buttonGap, 32);
        return new LockPromptLayout(width, height, Density.STANDARD, false, panel,
                title, input, submit, cancel, message);
    }

    private static LockPromptLayout compact(int width, int height) {
        int panelWidth = Math.min(328, width - 12);
        int panelHeight = Math.min(228, height - 12);
        UiRect panel = centered(width, height, panelWidth, panelHeight);
        int contentX = panel.x() + 14;
        int contentWidth = panel.width() - 28;
        boolean tight = panel.height() < 200;
        UiRect title = new UiRect(contentX, panel.y() + (tight ? 23 : 30), contentWidth, 20);
        UiRect input = new UiRect(contentX, panel.y() + (tight ? 58 : 82), contentWidth, 36);
        UiRect message = new UiRect(contentX, input.bottom() + 5, contentWidth, tight ? 24 : 31);
        int buttonY = panel.bottom() - (tight ? 32 : 38);
        int buttonGap = 8;
        int buttonWidth = (contentWidth - buttonGap) / 2;
        UiRect cancel = new UiRect(contentX, buttonY, buttonWidth, tight ? 24 : 28);
        UiRect submit = new UiRect(cancel.right() + buttonGap, buttonY,
                contentWidth - buttonWidth - buttonGap, cancel.height());
        return new LockPromptLayout(width, height, Density.COMPACT, false, panel,
                title, input, submit, cancel, message);
    }

    private static UiRect centered(int viewportWidth, int viewportHeight, int width, int height) {
        return new UiRect((viewportWidth - width) / 2, (viewportHeight - height) / 2, width, height);
    }

    private static UiRect insetViewport(int width, int height, int inset) {
        int appliedX = Math.min(inset, width / 2);
        int appliedY = Math.min(inset, height / 2);
        return new UiRect(appliedX, appliedY,
                Math.max(0, width - appliedX * 2), Math.max(0, height - appliedY * 2));
    }

    public boolean isTightCompact() {
        return density == Density.COMPACT && panel.height() < 200;
    }

    public enum Density {
        STANDARD,
        COMPACT
    }
}
