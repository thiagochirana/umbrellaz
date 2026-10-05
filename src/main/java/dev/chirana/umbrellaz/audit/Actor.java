package dev.chirana.umbrellaz.audit;

import java.util.UUID;

public record Actor(ActorType type, UUID uuid, String displayName) {
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;

    public Actor {
        if (type == null) {
            throw new IllegalArgumentException("Actor type must not be null");
        }
        if (type == ActorType.PLAYER && uuid == null) {
            throw new IllegalArgumentException("Player actors require a UUID");
        }
        if (type != ActorType.PLAYER && uuid != null) {
            throw new IllegalArgumentException("Non-player actors must not have a UUID");
        }
        AuditValidation.optionalDisplayName(displayName, MAX_DISPLAY_NAME_LENGTH, "Actor display name");
    }
}
