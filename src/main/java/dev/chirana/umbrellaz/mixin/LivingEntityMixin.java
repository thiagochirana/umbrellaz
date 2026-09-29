package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.player.HealthLockEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockLockedDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callbackInfo) {
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
        if (isLockedPlayer() && Float.compare(health, HealthLockEvents.lockedHealth((ServerPlayer) (Object) this)) != 0) {
            callbackInfo.cancel();
        }
    }

    private boolean isLockedPlayer() {
        return (Object) this instanceof ServerPlayer player
                && HealthLockEvents.isLocked(player);
    }

}
