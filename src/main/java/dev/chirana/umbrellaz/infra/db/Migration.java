package dev.chirana.umbrellaz.infra.db;

import java.time.Instant;

public record Migration(String migrationId, Instant createdAt, String sql) {
}
