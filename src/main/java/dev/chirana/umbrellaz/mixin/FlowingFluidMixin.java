package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.lock.LockEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$rejectProtectedFlow(LevelAccessor level, BlockPos position, BlockState state,
                                                Direction direction, FluidState fluidState, CallbackInfo callbackInfo) {
        if (level instanceof ServerLevel serverLevel && LockEvents.blocksEnvironmentalMutation(serverLevel, position)) {
            callbackInfo.cancel();
        }
    }
}
