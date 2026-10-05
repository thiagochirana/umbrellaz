package dev.chirana.umbrellaz.audit;

import java.util.UUID;

public record AuditQuery(Integer limit, Long cursor, String action, UUID actorUuid, Long occurredSinceMs) {
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 50;

    public AuditQuery {
        int requestedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("Audit page size must be between 1 and " + MAX_LIMIT);
        }
        limit = requestedLimit;
        if (cursor != null && cursor < 1) {
            throw new IllegalArgumentException("Audit cursor must be positive");
        }
        if (action != null) {
            AuditValidation.stableCode(action, AuditEvent.MAX_ACTION_LENGTH, "Audit action filter");
        }
        if (occurredSinceMs != null && occurredSinceMs < 0) {
            throw new IllegalArgumentException("Audit since timestamp must not be negative");
        }
    }

    public static AuditQuery defaults() {
        return new AuditQuery(DEFAULT_LIMIT, null, null, null, null);
    }
}
