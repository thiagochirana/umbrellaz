package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.auth.AuthEvents;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockMovement(ServerboundMovePlayerPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockInventoryClick(ServerboundContainerClickPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockRecipePlacement(ServerboundPlaceRecipePacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockBookEditing(ServerboundEditBookPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockPickItemFromBlock(ServerboundPickItemFromBlockPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockPickItemFromEntity(ServerboundPickItemFromEntityPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleBundleItemSelectedPacket", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockBundleItemSelection(ServerboundSelectBundleItemPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockItemRenaming(ServerboundRenameItemPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockTradeSelection(ServerboundSelectTradePacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockContainerButtonClick(ServerboundContainerButtonClickPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleSetBeaconPacket", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockBeaconMutation(ServerboundSetBeaconPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleContainerSlotStateChanged", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockContainerSlotStateChange(ServerboundContainerSlotStateChangedPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockCreativeInventory(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callbackInfo) {
        if (AuthEvents.isBlocked(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockCommand(ServerboundChatCommandPacket packet, CallbackInfo callbackInfo) {
        if (!AuthEvents.isAdministrator(player.getUUID())) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void umbrellaz$blockSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo callbackInfo) {
        if (!AuthEvents.isAdministrator(player.getUUID())) {
            callbackInfo.cancel();
        }
    }
}
