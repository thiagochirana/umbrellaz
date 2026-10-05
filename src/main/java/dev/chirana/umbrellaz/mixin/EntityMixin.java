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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "interact", at = @At("RETURN"))
    private void umbrellaz$auditInteraction(Player player, InteractionHand hand, Vec3 hitPosition,
                                             CallbackInfoReturnable<InteractionResult> callbackInfo) {
        try {
            Entity entity = (Entity) (Object) this;
            InteractionResult result = callbackInfo.getReturnValue();
            if (!(entity.level() instanceof ServerLevel level)
                    || !(player instanceof ServerPlayer serverPlayer)
                    || !AuditInteractionSupport.accepted(result)) {
                return;
            }
            String resultCode = AuditInteractionSupport.resultCode(result);
            String handCode = AuditInteractionSupport.handCode(hand);
            if (resultCode == null || handCode == null) {
                return;
            }
            record(level, serverPlayer, entity, handCode, resultCode, hitPosition);
        } catch (Throwable ignored) {
            // Audit is isolated from entity behavior, including custom entity failures.
        }
    }

    private static void record(ServerLevel level, ServerPlayer player, Entity entity,
                               String hand, String result, Vec3 hitPosition) {
        ServerRuntimeRegistry.findReady(level.getServer()).ifPresent(runtime -> {
            try {
                String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                        .toString().toLowerCase(Locale.ROOT);
                BlockPos boundedHit = AuditInteractionSupport.boundedPosition(hitPosition);
                AuditPayload payload;
                if (boundedHit != null) {
                    payload = AuditPayload.entityInteractedAt(entityType, entity.getUUID(), hand, result,
                            player.getUUID(), player.getName().getString(),
                            level.dimension().identifier().toString(), boundedHit.getX(), boundedHit.getY(), boundedHit.getZ());
                } else {
                    payload = AuditPayload.entityInteracted(entityType, entity.getUUID(), hand, result,
                            player.getUUID(), player.getName().getString());
                }
                runtime.auditService().record(new AuditRecordRequest(
                        AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                                player.getName().getString())),
                        Source.EVENT, AuditActions.ENTITY_INTERACTED, Outcome.SUCCESS,
                        new Target("entity", entity.getUUID().toString()), "interacted", 1, payload,
                        AuditDelivery.BEST_EFFORT)).exceptionally(ignored -> null);
            } catch (Throwable ignored) {
                // BEST_EFFORT delivery must not affect the caller.
            }
        });
    }
}
