package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.auth.AuthEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandSourceStack.class)
public abstract class ServerCommandSourceMixin {
    @Inject(method = "permissions", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$grantAdministratorPermission(CallbackInfoReturnable<PermissionSet> callbackInfo) {
        CommandSourceStack source = (CommandSourceStack) (Object) this;
        if (source.getEntity() instanceof ServerPlayer player && AuthEvents.isAdministrator(player)) {
            callbackInfo.setReturnValue(PermissionSet.ALL_PERMISSIONS);
        }
    }
}
