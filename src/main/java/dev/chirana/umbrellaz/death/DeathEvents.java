package dev.chirana.umbrellaz.death;

import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.audit.Target;
import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server-side death audit adapter. Registration is intentionally owned by a later runtime lane. */
public final class DeathEvents {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private DeathEvents() {
    }

    public static void installGlobalCallbacks() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        ServerLivingEntityEvents.AFTER_DEATH.register(DeathEvents::afterDeath);
    }

    public static void afterDeath(LivingEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level) || source == null) return;
        ServerRuntimeRegistry.findReady(level.getServer())
                .map(runtime -> runtime.auditService())
                .ifPresent(audit -> recordDeath(audit, victim, source));
    }

    private static void recordDeath(AuditService audit, LivingEntity victim, DamageSource source) {
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        String cause = stableCause(source.getMsgId());
        String itemType = itemType(source.getWeaponItem());
        Entity sourceEntity = source.getEntity();
        ServerPlayer killer = sourceEntity instanceof ServerPlayer player ? player : null;

        if (victim instanceof ServerPlayer player) {
            recordPlayerDeath(audit, player, entityType, cause, itemType);
            if (killer != null && killer != player) {
                recordPlayerKill(audit, killer, player, entityType, cause, itemType);
            }
            return;
        }

        if (killer != null) {
            recordEntityKill(audit, killer, victim, entityType, cause, itemType);
        } else {
            recordEntityDeath(audit, victim, entityType, cause, itemType);
        }
    }

    private static void recordPlayerDeath(AuditService audit, ServerPlayer victim,
                                          String entityType, String cause, String itemType) {
        AuditPayload payload = AuditPayload.forAction(AuditActions.PLAYER_DIED,
                AuditPayload.playerUuid(victim.getUUID()), AuditPayload.playerName(victim.getName().getString()),
                AuditPayload.cause(cause), AuditPayload.itemType(itemType));
        record(audit, new Actor(ActorType.PLAYER, victim.getUUID(), victim.getName().getString()),
                AuditActions.PLAYER_DIED, new Target("player", victim.getUUID().toString()), payload,
                "death");
    }

    private static void recordPlayerKill(AuditService audit, ServerPlayer killer, ServerPlayer victim,
                                         String entityType, String cause, String itemType) {
        AuditPayload payload = AuditPayload.forAction(AuditActions.PLAYER_KILLED,
                AuditPayload.playerUuid(victim.getUUID()), AuditPayload.playerName(victim.getName().getString()),
                AuditPayload.entityType(entityType), AuditPayload.cause(cause), AuditPayload.itemType(itemType),
                AuditPayload.killerUuid(killer.getUUID()), AuditPayload.killerName(killer.getName().getString()));
        record(audit, new Actor(ActorType.PLAYER, killer.getUUID(), killer.getName().getString()),
                AuditActions.PLAYER_KILLED, new Target("player", victim.getUUID().toString()), payload,
                "player_kill");
    }

    private static void recordEntityKill(AuditService audit, ServerPlayer killer, LivingEntity victim,
                                         String entityType, String cause, String itemType) {
        AuditPayload payload = AuditPayload.forAction(AuditActions.ENTITY_KILLED,
                AuditPayload.entityType(entityType), AuditPayload.cause(cause), AuditPayload.itemType(itemType),
                AuditPayload.killerUuid(killer.getUUID()), AuditPayload.killerName(killer.getName().getString()));
        record(audit, new Actor(ActorType.PLAYER, killer.getUUID(), killer.getName().getString()),
                AuditActions.ENTITY_KILLED, new Target("entity", victim.getUUID().toString()), payload,
                "player_kill");
    }

    private static void recordEntityDeath(AuditService audit, LivingEntity victim,
                                          String entityType, String cause, String itemType) {
        AuditPayload payload = AuditPayload.forAction(AuditActions.ENTITY_DIED,
                AuditPayload.entityType(entityType), AuditPayload.cause(cause), AuditPayload.itemType(itemType));
        record(audit, new Actor(ActorType.SYSTEM, null, "server"), AuditActions.ENTITY_DIED,
                new Target("entity", victim.getUUID().toString()), payload, "death");
    }

    private static void record(AuditService audit, Actor actor, String action, Target target,
                               AuditPayload payload, String reasonCode) {
        try {
            audit.record(new AuditRecordRequest(AuditRecordContext.forActor(actor), Source.EVENT, action,
                    Outcome.SUCCESS, target, reasonCode, 1, payload, AuditDelivery.BEST_EFFORT))
                    .exceptionally(failure -> null);
        } catch (RuntimeException ignored) {
            // Death auditing must not interfere with vanilla death processing.
        }
    }

    private static String itemType(ItemStack weapon) {
        return BuiltInRegistries.ITEM.getKey(weapon == null ? ItemStack.EMPTY.getItem() : weapon.getItem())
                .toString().toLowerCase(Locale.ROOT);
    }

    private static String stableCause(String messageId) {
        if (messageId == null || messageId.isBlank()) return "unknown";
        String normalized = messageId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]", "_");
        return normalized.isEmpty() || !Character.isLetterOrDigit(normalized.charAt(0))
                ? "unknown" : normalized;
    }
}
