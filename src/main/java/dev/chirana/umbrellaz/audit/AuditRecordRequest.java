package dev.chirana.umbrellaz.audit;

import java.util.Objects;

public record AuditRecordRequest(
        AuditRecordContext context,
        Source source,
        String action,
        Outcome outcome,
        Target target,
        String reasonCode,
        int payloadVersion,
        AuditPayload payload,
        AuditDelivery delivery
) {
    public AuditRecordRequest {
        Objects.requireNonNull(context, "Audit context must not be null");
        Objects.requireNonNull(source, "Audit source must not be null");
        Objects.requireNonNull(action, "Audit action must not be null");
        Objects.requireNonNull(outcome, "Audit outcome must not be null");
        Objects.requireNonNull(payload, "Audit payload must not be null");
        Objects.requireNonNull(delivery, "Audit delivery must not be null");
    }
}
