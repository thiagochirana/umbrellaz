package dev.chirana.umbrellaz.world;

public enum WorldSkyMode {
    CLEAN("limpo", 0.0F, 0.0F),
    RAIN("chuva", 1.0F, 0.0F),
    STORM("tempestade", 1.0F, 1.0F);

    private final String displayName;
    private final float rainLevel;
    private final float thunderLevel;

    WorldSkyMode(String displayName, float rainLevel, float thunderLevel) {
        this.displayName = displayName;
        this.rainLevel = rainLevel;
        this.thunderLevel = thunderLevel;
    }

    public String displayName() {
        return displayName;
    }

    public float rainLevel() {
        return rainLevel;
    }

    public float thunderLevel() {
        return thunderLevel;
    }
}
