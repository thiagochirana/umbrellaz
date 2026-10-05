package dev.chirana.umbrellaz.audit;

import java.util.UUID;

/** Immutable, server-thread snapshot of one damage callback. */
public record DamageAuditObservation(
        UUID attackerPlayerUuid,
        UUID victimPlayerUuid,
        String victimEntityType,
        String cause,
        String itemType,
        String world,
        double baseDamage,
        double reportedDamage,
        boolean shieldBlocked
) {
    public DamageAuditObservation {
        if (victimPlayerUuid != null && victimEntityType != null) {
            throw new IllegalArgumentException("Player victims must not have an entity type");
        }
        if (victimPlayerUuid == null && (victimEntityType == null || victimEntityType.isBlank())) {
            throw new IllegalArgumentException("Non-player victims require an entity type");
        }
        if (cause == null || cause.isBlank() || itemType == null || itemType.isBlank()
                || world == null || world.isBlank()) {
            throw new IllegalArgumentException("Damage registry identifiers must not be blank");
        }
    }

    public boolean accepted() {
        return (attackerPlayerUuid != null || victimPlayerUuid != null)
                && !shieldBlocked && Double.isFinite(reportedDamage) && reportedDamage > 0.0
                && Double.isFinite(baseDamage) && baseDamage >= 0.0;
    }
}
