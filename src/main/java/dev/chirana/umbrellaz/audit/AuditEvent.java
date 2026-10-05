package dev.chirana.umbrellaz.audit;

import java.util.UUID;

public record AuditEvent(
        UUID eventId,
        UUID runtimeId,
        UUID correlationId,
        long occurredAtMs,
        long recordedAtMs,
        Source source,
        String action,
        Outcome outcome,
        Actor actor,
        Target target,
        String reasonCode,
        int payloadVersion,
        AuditPayload payload
) {
    public static final int MAX_ACTION_LENGTH = 128;
    public static final int MAX_REASON_CODE_LENGTH = 64;

    public AuditEvent {
        if (eventId == null || runtimeId == null || correlationId == null) {
            throw new IllegalArgumentException("Audit event identity must not be null");
        }
        if (occurredAtMs < 0 || recordedAtMs < 0) {
            throw new IllegalArgumentException("Audit timestamps must not be negative");
        }
        if (source == null || outcome == null || actor == null || payload == null) {
            throw new IllegalArgumentException("Audit event fields must not be null");
        }
        AuditValidation.stableCode(action, MAX_ACTION_LENGTH, "Action");
        payload.validateForAction(action);
        if (reasonCode != null) {
            AuditValidation.stableCode(reasonCode, MAX_REASON_CODE_LENGTH, "Reason code");
        }
        if (payloadVersion < 1) {
            throw new IllegalArgumentException("Payload version must be positive");
        }
    }
}
