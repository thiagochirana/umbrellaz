package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.lock.LockEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void umbrellaz$removeProtectedPositions(List<BlockPos> positions, CallbackInfo callbackInfo) {
        ServerLevel level = ((ServerExplosion) (Object) this).level();
        positions.removeIf(position -> LockEvents.blocksEnvironmentalMutation(level, position));
    }
}
