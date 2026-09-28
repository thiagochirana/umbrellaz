package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.blocks.BlocksEvents;
import dev.chirana.umbrellaz.lock.LockEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$rejectLockedChestMerge(BlockPlaceContext context, BlockState state,
                                                  CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!LockEvents.beforePlacement(context.getLevel(), context.getPlayer(), context.getClickedPos(), state)) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void umbrellaz$recordSuccessfulPlacement(BlockPlaceContext context, BlockState state,
                                                      CallbackInfoReturnable<Boolean> callbackInfo) {
        if (callbackInfo.getReturnValue()) {
            BlockPos position = context.getClickedPos();
            BlocksEvents.afterSuccessfulPlayerPlacement(context.getLevel(), context.getPlayer(), position, state);
            LockEvents.afterSuccessfulPlacement(context.getLevel(), context.getPlayer(), position, state);
        }
    }
}
