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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Shadow
    private int stateId;

    @Unique
    private static final ThreadLocal<Deque<ClickSnapshot>> UMBRELLAZ_SNAPSHOTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "clicked", at = @At("HEAD"))
    private void umbrellaz$snapshotClick(int slotId, int button, ContainerInput clickType, Player player,
                                         CallbackInfo callbackInfo) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || player.level().isClientSide()) {
                return;
            }
            UMBRELLAZ_SNAPSHOTS.get().push(new ClickSnapshot(
                    fingerprint(slotId), AuditInteractionSupport.clickTypeCode(clickType), serverPlayer));
        } catch (Throwable ignored) {
            // Snapshotting is deliberately advisory and must not affect vanilla behavior.
        }
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void umbrellaz$auditChangedClick(int slotId, int button, ContainerInput clickType, Player player,
                                             CallbackInfo callbackInfo) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || player.level().isClientSide()) {
                return;
            }
            ClickSnapshot before = UMBRELLAZ_SNAPSHOTS.get().poll();
            if (before == null || before.player() != serverPlayer || before.fingerprint() == null) {
                return;
            }
            AuditInteractionSupport.ContainerFingerprint after = fingerprint(slotId);
            if (!AuditInteractionSupport.fingerprintsChanged(before.fingerprint(), after)
                    || after == null || before.clickType() == null) {
                return;
            }
            record(serverPlayer, before.clickType(), after);
        } catch (Throwable ignored) {
            // Audit is isolated from container behavior, including invalid clicks.
        }
    }

    private AuditInteractionSupport.ContainerFingerprint fingerprint(int slotId) {
        String menuType = AuditInteractionSupport.menuType(getClass());
        ItemStack carried = ((AbstractContainerMenu) (Object) this).getCarried();
        ItemFingerprint carriedFingerprint = itemFingerprint(carried);
        if (carriedFingerprint == null) {
            return null;
        }

        boolean slotPresent = slotId >= 0 && slotId < slots().size();
        int boundedSlotId = slotPresent ? slotId : -1;
        ItemFingerprint slotFingerprint = new ItemFingerprint("minecraft:air", 0);
        if (slotPresent) {
            Slot slot = slots().get(slotId);
            if (slot == null) {
                slotPresent = false;
                boundedSlotId = -1;
            } else {
                slotFingerprint = itemFingerprint(slot.getItem());
                if (slotFingerprint == null) {
                    return null;
                }
            }
        }
        return new AuditInteractionSupport.ContainerFingerprint(menuType, stateId,
                carriedFingerprint.type(), carriedFingerprint.count(), boundedSlotId,
                slotFingerprint.type(), slotFingerprint.count(), slotPresent);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Slot> slots() {
        return ((AbstractContainerMenu) (Object) this).slots;
    }

    private static ItemFingerprint itemFingerprint(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            String type = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
            return new ItemFingerprint(type, AuditInteractionSupport.boundedCount(stack.getCount()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void record(ServerPlayer player, String clickType,
                        AuditInteractionSupport.ContainerFingerprint fingerprint) {
        ServerRuntimeRegistry.findReady(player.level().getServer()).ifPresent(runtime -> {
            try {
                AuditService audit = runtime.auditService();
                AuditPayload payload;
                if (fingerprint.slotPresent()) {
                    payload = AuditPayload.containerMutatedAtSlot(fingerprint.menuType(), fingerprint.stateId(),
                            clickType, fingerprint.carriedItemType(), fingerprint.carriedCount(),
                            fingerprint.slotId(), fingerprint.slotItemType(), fingerprint.slotItemCount(),
                            player.getUUID(), player.getName().getString());
                } else {
                    payload = AuditPayload.containerMutated(fingerprint.menuType(), fingerprint.stateId(), clickType,
                            fingerprint.carriedItemType(), fingerprint.carriedCount(),
                            player.getUUID(), player.getName().getString());
                }
                String targetId = fingerprint.menuType() + ":" + ((AbstractContainerMenu) (Object) this).containerId;
                audit.record(new AuditRecordRequest(
                        AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                                player.getName().getString())),
                        Source.EVENT, AuditActions.CONTAINER_MUTATED, Outcome.SUCCESS,
                        new Target("container", targetId), "mutated", 1, payload,
                        AuditDelivery.BEST_EFFORT)).exceptionally(ignored -> null);
            } catch (Throwable ignored) {
                // BEST_EFFORT delivery must not affect the caller.
            }
        });
    }

    private record ClickSnapshot(AuditInteractionSupport.ContainerFingerprint fingerprint,
                                 String clickType, ServerPlayer player) {
    }

    private record ItemFingerprint(String type, int count) {
    }
}
