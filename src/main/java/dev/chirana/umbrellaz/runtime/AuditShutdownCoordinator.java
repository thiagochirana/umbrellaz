package dev.chirana.umbrellaz.runtime;

import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class AuditShutdownCoordinator {
    private final AuditService auditService;
    private final DatabaseExecutor databaseExecutor;

    AuditShutdownCoordinator(AuditService auditService, DatabaseExecutor databaseExecutor) {
        this.auditService = Objects.requireNonNull(auditService, "Audit service must not be null");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "Database executor must not be null");
    }

    CompletableFuture<Void> shutdown() {
        return auditService.shutdown().whenComplete((ignored, failure) -> databaseExecutor.close());
    }
}
