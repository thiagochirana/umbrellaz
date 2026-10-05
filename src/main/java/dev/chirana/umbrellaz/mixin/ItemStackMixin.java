package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.audit.Target;
import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "use", at = @At("RETURN"))
    private void umbrellaz$auditUse(Level level, Player player, net.minecraft.world.InteractionHand hand,
                                    CallbackInfoReturnable<InteractionResult> callbackInfo) {
        try {
            InteractionResult result = callbackInfo.getReturnValue();
            if (level == null || level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                    || !AuditInteractionSupport.accepted(result)) {
                return;
            }
            String resultCode = AuditInteractionSupport.resultCode(result);
            String handCode = AuditInteractionSupport.handCode(hand);
            if (resultCode == null || handCode == null || !(level instanceof ServerLevel serverLevel)) {
                return;
            }
            recordUse(serverLevel, serverPlayer, itemType((ItemStack) (Object) this), handCode, resultCode);
        } catch (Throwable ignored) {
            // Audit is isolated from item behavior, including custom item failures.
        }
    }

    @Inject(method = "useOn", at = @At("RETURN"))
    private void umbrellaz$auditUseOn(UseOnContext context,
                                      CallbackInfoReturnable<InteractionResult> callbackInfo) {
        try {
            InteractionResult result = callbackInfo.getReturnValue();
            if (context == null || !AuditInteractionSupport.accepted(result)
                    || !(context.getLevel() instanceof ServerLevel level)
                    || !(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            String resultCode = AuditInteractionSupport.resultCode(result);
            String handCode = AuditInteractionSupport.handCode(context.getHand());
            if (resultCode == null || handCode == null) {
                return;
            }
            recordUseOn(level, player, itemType((ItemStack) (Object) this), handCode, resultCode,
                    context.getClickedPos());
        } catch (Throwable ignored) {
            // Audit is isolated from item behavior, including custom item failures.
        }
    }

    private static void recordUse(ServerLevel level, ServerPlayer player, String itemType,
                                  String hand, String result) {
        ServerRuntimeRegistry.findReady(level.getServer()).ifPresent(runtime -> {
            try {
                AuditService audit = runtime.auditService();
                AuditPayload payload = AuditPayload.itemUsed(itemType, hand, result,
                        player.getUUID(), player.getName().getString());
                audit.record(new AuditRecordRequest(
                        AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                                player.getName().getString())),
                        Source.EVENT, AuditActions.ITEM_USED, Outcome.SUCCESS,
                        new Target("item", itemType), "used", 1, payload, AuditDelivery.BEST_EFFORT))
                        .exceptionally(ignored -> null);
            } catch (Throwable ignored) {
                // BEST_EFFORT delivery must not affect the caller.
            }
        });
    }

    private static void recordUseOn(ServerLevel level, ServerPlayer player, String itemType,
                                    String hand, String result, BlockPos clickedPos) {
        ServerRuntimeRegistry.findReady(level.getServer()).ifPresent(runtime -> {
            try {
                AuditService audit = runtime.auditService();
                AuditPayload payload;
                if (AuditInteractionSupport.bounded(clickedPos)) {
                    payload = AuditPayload.itemUsedAt(itemType, hand, result, player.getUUID(),
                            player.getName().getString(), level.dimension().identifier().toString(),
                            clickedPos.getX(), clickedPos.getY(), clickedPos.getZ());
                } else {
                    payload = AuditPayload.itemUsed(itemType, hand, result, player.getUUID(),
                            player.getName().getString());
                }
                audit.record(new AuditRecordRequest(
                        AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                                player.getName().getString())),
                        Source.EVENT, AuditActions.ITEM_USED, Outcome.SUCCESS,
                        new Target("item", itemType), "used_on", 1, payload, AuditDelivery.BEST_EFFORT))
                        .exceptionally(ignored -> null);
            } catch (Throwable ignored) {
                // BEST_EFFORT delivery must not affect the caller.
            }
        });
    }

    private static String itemType(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }
}
