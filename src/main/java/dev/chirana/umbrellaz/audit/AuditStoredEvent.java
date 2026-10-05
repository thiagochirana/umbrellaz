package dev.chirana.umbrellaz.audit;

import java.util.Objects;

public record AuditStoredEvent(long sequence, AuditEvent event, boolean legacyOpaquePayload) {
    public AuditStoredEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("Audit sequence must be positive");
        }
        Objects.requireNonNull(event, "Audit event must not be null");
    }
}
