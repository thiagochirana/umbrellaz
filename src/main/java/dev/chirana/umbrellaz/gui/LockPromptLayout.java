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
        Density density = !belowMinimum && safeWidth >= 420 && safeHeight >= 280
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
        int panelWidth = Math.min(320, width - 24);
        int panelHeight = Math.min(190, height - 20);
        UiRect panel = centered(width, height, panelWidth, panelHeight);
        int contentX = panel.x() + 20;
        int contentWidth = panel.width() - 40;
        UiRect title = new UiRect(contentX, panel.y() + 18, contentWidth, 12);
        UiRect input = new UiRect(contentX, panel.y() + 52, contentWidth, 36);
        UiRect message = new UiRect(contentX, input.bottom() + 7, contentWidth, 22);
        int buttonY = panel.bottom() - 38;
        int buttonGap = 8;
        int buttonWidth = (contentWidth - buttonGap) / 2;
        UiRect cancel = new UiRect(contentX, buttonY, buttonWidth, 26);
        UiRect submit = new UiRect(cancel.right() + buttonGap, buttonY,
                contentWidth - buttonWidth - buttonGap, cancel.height());
        return new LockPromptLayout(width, height, Density.STANDARD, false, panel,
                title, input, submit, cancel, message);
    }

    private static LockPromptLayout compact(int width, int height) {
        int panelWidth = Math.min(280, width - 12);
        int panelHeight = Math.min(164, height - 12);
        UiRect panel = centered(width, height, panelWidth, panelHeight);
        int contentX = panel.x() + 12;
        int contentWidth = panel.width() - 24;
        UiRect title = new UiRect(contentX, panel.y() + 15, contentWidth, 12);
        UiRect input = new UiRect(contentX, panel.y() + 47, contentWidth, 34);
        UiRect message = new UiRect(contentX, input.bottom() + 5, contentWidth, 18);
        int buttonY = panel.bottom() - 31;
        int buttonGap = 8;
        int buttonWidth = (contentWidth - buttonGap) / 2;
        UiRect cancel = new UiRect(contentX, buttonY, buttonWidth, 23);
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
        return density == Density.COMPACT && panel.height() < 170;
    }

    public enum Density {
        STANDARD,
        COMPACT
    }
}
