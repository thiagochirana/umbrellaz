package dev.chirana.umbrellaz.audit;

public record AuditWriterStatus(
        long accepted,
        long dropped,
        long failed,
        long rejected,
        int queued,
        boolean healthy,
        boolean closed
) {
}
