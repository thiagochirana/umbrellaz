package dev.chirana.umbrellaz.player;

public record PlayerResolution(Status status, Player player) {
    public enum Status {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS,
        NOT_READY
    }

    public static PlayerResolution found(Player player) {
        return new PlayerResolution(Status.FOUND, player);
    }

    public static PlayerResolution notFound() {
        return new PlayerResolution(Status.NOT_FOUND, null);
    }

    public static PlayerResolution ambiguous() {
        return new PlayerResolution(Status.AMBIGUOUS, null);
    }

    public static PlayerResolution notReady() {
        return new PlayerResolution(Status.NOT_READY, null);
    }
}
