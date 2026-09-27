package dev.chirana.umbrellaz.player;

import net.minecraft.server.level.ServerPlayer;

public record OnlinePlayerResolution(Status status, ServerPlayer player) {
    public enum Status {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS,
        NOT_READY
    }

    public static OnlinePlayerResolution found(ServerPlayer player) {
        return new OnlinePlayerResolution(Status.FOUND, player);
    }

    public static OnlinePlayerResolution notFound() {
        return new OnlinePlayerResolution(Status.NOT_FOUND, null);
    }

    public static OnlinePlayerResolution ambiguous() {
        return new OnlinePlayerResolution(Status.AMBIGUOUS, null);
    }

    public static OnlinePlayerResolution notReady() {
        return new OnlinePlayerResolution(Status.NOT_READY, null);
    }
}
