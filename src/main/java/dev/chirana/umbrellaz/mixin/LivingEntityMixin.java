package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.player.HealthLockEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockLockedDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (isLockedPlayer()) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockLockedHealing(float amount, CallbackInfo callbackInfo) {
        if (isLockedPlayer()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockLockedHealthChange(float health, CallbackInfo callbackInfo) {
        if (isLockedPlayer() && Float.compare(health, HealthLockEvents.lockedHealth(playerUuid())) != 0) {
            callbackInfo.cancel();
        }
    }

    private boolean isLockedPlayer() {
        return (Object) this instanceof ServerPlayerEntity player
                && HealthLockEvents.isLocked(player.getUuid());
    }

    private java.util.UUID playerUuid() {
        return ((ServerPlayerEntity) (Object) this).getUuid();
    }
}
