package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.auth.AuthEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockPickup(PlayerEntity player, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUuid())) {
            callbackInfo.cancel();
        }
    }
}
