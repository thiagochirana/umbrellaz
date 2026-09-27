package dev.chirana.umbrellaz.player;

import java.util.UUID;

public record AliasUpdate(Status status, UUID playerUuid, String alias) {
    public enum Status {
        UPDATED,
        UNKNOWN_PLAYER,
        AMBIGUOUS_PLAYER,
        CONFLICT,
        USERNAME_CONFLICT,
        INVALID_ALIAS,
        RESERVED_ALIAS,
        NOT_READY
    }

    public static AliasUpdate unknownPlayer() {
        return new AliasUpdate(Status.UNKNOWN_PLAYER, null, null);
    }

    public static AliasUpdate conflict() {
        return new AliasUpdate(Status.CONFLICT, null, null);
    }

    public static AliasUpdate ambiguousPlayer() {
        return new AliasUpdate(Status.AMBIGUOUS_PLAYER, null, null);
    }

    public static AliasUpdate usernameConflict() {
        return new AliasUpdate(Status.USERNAME_CONFLICT, null, null);
    }

    public static AliasUpdate invalidAlias() {
        return new AliasUpdate(Status.INVALID_ALIAS, null, null);
    }

    public static AliasUpdate reservedAlias() {
        return new AliasUpdate(Status.RESERVED_ALIAS, null, null);
    }

    public static AliasUpdate notReady() {
        return new AliasUpdate(Status.NOT_READY, null, null);
    }

    public static AliasUpdate updated(UUID playerUuid, String alias) {
        return new AliasUpdate(Status.UPDATED, playerUuid, alias);
    }
}
