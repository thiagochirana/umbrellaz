package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.lock.LockEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
    @Shadow @Final private Level level;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void umbrellaz$rejectProtectedMovement(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!callbackInfo.getReturnValueZ() || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        PistonStructureResolver resolver = (PistonStructureResolver) (Object) this;
        if (resolver.getToPush().stream().anyMatch(position ->
                LockEvents.blocksEnvironmentalMutation(serverLevel, position))
                || resolver.getToDestroy().stream().anyMatch(position ->
                LockEvents.blocksEnvironmentalMutation(serverLevel, position))) {
            callbackInfo.setReturnValue(false);
        }
    }
}
