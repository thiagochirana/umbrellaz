package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.auth.AuthEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerCommandSource.class)
public abstract class ServerCommandSourceMixin {
    @Inject(method = "hasPermissionLevel", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$grantAdministratorPermission(int level, CallbackInfoReturnable<Boolean> callbackInfo) {
        ServerCommandSource source = (ServerCommandSource) (Object) this;
        Entity entity = source.getEntity();
        if (entity != null && AuthEvents.isAdministrator(entity.getUuid())) {
            callbackInfo.setReturnValue(true);
        }
    }
}
