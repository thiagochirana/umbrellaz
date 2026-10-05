package dev.chirana.umbrellaz.audit;

import java.util.Objects;
import java.util.UUID;

public record AuditRecordContext(Actor actor, UUID correlationId) {
    public AuditRecordContext {
        Objects.requireNonNull(actor, "Audit actor must not be null");
        Objects.requireNonNull(correlationId, "Audit correlation id must not be null");
    }

    public static AuditRecordContext forActor(Actor actor) {
        return new AuditRecordContext(actor, UUID.randomUUID());
    }
}
