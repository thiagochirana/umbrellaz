package dev.chirana.umbrellaz.blocks;

public final class BlocksRuntimeState {
    private volatile boolean treeEzBreakEnabled;
    private volatile boolean oresEzBreakEnabled;

    public boolean treeEzBreakEnabled() {
        return treeEzBreakEnabled;
    }

    public void setTreeEzBreakEnabled(boolean enabled) {
        treeEzBreakEnabled = enabled;
    }

    public boolean oresEzBreakEnabled() {
        return oresEzBreakEnabled;
    }

    public void setOresEzBreakEnabled(boolean enabled) {
        oresEzBreakEnabled = enabled;
    }
}
