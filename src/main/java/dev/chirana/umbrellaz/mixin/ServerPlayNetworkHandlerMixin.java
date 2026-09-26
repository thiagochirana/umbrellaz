package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.auth.AuthEvents;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockMovement(PlayerMoveC2SPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUuid())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockPlayerAction(PlayerActionC2SPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUuid())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockInventoryClick(ClickSlotC2SPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUuid())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockCreativeInventory(CreativeInventoryActionC2SPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUuid())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "onCommandExecution", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockCommand(CommandExecutionC2SPacket packet, CallbackInfo callbackInfo) {
        if (!AuthEvents.isAdministrator(player.getUuid())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "onChatCommandSigned", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockSignedCommand(ChatCommandSignedC2SPacket packet, CallbackInfo callbackInfo) {
        if (!AuthEvents.isAdministrator(player.getUuid())) {
            callbackInfo.cancel();
        }
    }
}
