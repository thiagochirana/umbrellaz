package dev.chirana.umbrellaz.audit;

import java.util.List;

public record AuditPage(List<AuditStoredEvent> events, Long nextCursor) {
    public AuditPage {
        events = List.copyOf(events);
        if (nextCursor != null && nextCursor < 1) {
            throw new IllegalArgumentException("Audit cursor must be positive");
        }
    }
}
