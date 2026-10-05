package dev.chirana.umbrellaz.audit;

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

/** Global adapter for bounded, player-involved damage observations. */
public final class DamageAuditEvents {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private DamageAuditEvents() {}

    public static void installGlobalCallbacks() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        ServerLivingEntityEvents.AFTER_DAMAGE.register(DamageAuditEvents::afterDamage);
    }

    public static void afterDamage(LivingEntity victim, DamageSource source,
                                   float baseDamage, float reportedDamage, boolean blocked) {
        if (!(victim.level() instanceof ServerLevel level) || source == null) return;
        if (blocked || !Float.isFinite(reportedDamage) || reportedDamage <= 0.0f) return;
        if (victim.isDeadOrDying()) return;
        ServerPlayer victimPlayer = victim instanceof ServerPlayer player ? player : null;
        Entity sourceEntity = source.getEntity();
        ServerPlayer attacker = sourceEntity instanceof ServerPlayer player ? player : null;
        if (victimPlayer == null && attacker == null) return;
        String entityType = victimPlayer == null
                ? BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString() : null;
        DamageAuditObservation observation;
        try {
            observation = new DamageAuditObservation(attacker == null ? null : attacker.getUUID(),
                    victimPlayer == null ? null : victimPlayer.getUUID(), entityType,
                    stableCause(source.getMsgId()), itemType(source.getWeaponItem()),
                    level.dimension().identifier().toString(), baseDamage, reportedDamage, false);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        ServerRuntimeRegistry.findReady(level.getServer()).ifPresent(runtime ->
                runtime.damageAuditAggregator().observe(observation, System.currentTimeMillis()));
    }

    private static String itemType(ItemStack weapon) {
        return BuiltInRegistries.ITEM.getKey(weapon == null ? ItemStack.EMPTY.getItem() : weapon.getItem())
                .toString().toLowerCase(Locale.ROOT);
    }

    private static String stableCause(String messageId) {
        if (messageId == null || messageId.isBlank()) return "unknown";
        String normalized = messageId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]", "_");
        return normalized.isEmpty() || !Character.isLetterOrDigit(normalized.charAt(0)) ? "unknown" : normalized;
    }
}
