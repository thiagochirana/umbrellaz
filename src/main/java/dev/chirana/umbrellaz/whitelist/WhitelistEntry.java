package dev.chirana.umbrellaz.whitelist;

import java.time.Instant;
import java.util.UUID;

public record WhitelistEntry(UUID playerUuid, Instant createdAt, String createdBy) {
}
