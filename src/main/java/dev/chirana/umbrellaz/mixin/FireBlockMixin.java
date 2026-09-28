package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.lock.LockEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$rejectProtectedBurnout(Level level, BlockPos position, int chance,
                                                  RandomSource random, int age, CallbackInfo callbackInfo) {
        if (level instanceof ServerLevel serverLevel && LockEvents.blocksEnvironmentalMutation(serverLevel, position)) {
            callbackInfo.cancel();
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean umbrellaz$rejectProtectedSpread(ServerLevel level, BlockPos position, BlockState state) {
        if (LockEvents.blocksEnvironmentalMutation(level, position)) {
            return false;
        }
        return level.setBlockAndUpdate(position, state);
    }
}
