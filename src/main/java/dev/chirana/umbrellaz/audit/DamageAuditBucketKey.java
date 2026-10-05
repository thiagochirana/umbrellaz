package dev.chirana.umbrellaz.audit;

import java.util.UUID;

/** Stable identity of a damage summary bucket. */
public record DamageAuditBucketKey(
        UUID attackerPlayerUuid,
        UUID victimPlayerUuid,
        String victimEntityType,
        String cause,
        String itemType,
        String world
) {
}
