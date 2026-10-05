package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.player.FoodLockEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockLockedFoodTick(ServerPlayer player, CallbackInfo callbackInfo) {
        if (FoodLockEvents.isLocked(player)) {
            callbackInfo.cancel();
        }
    }
}
