package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.audit.ExplosionAuditEvents;
import dev.chirana.umbrellaz.lock.LockEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Unique
    private List<ExplosionAuditEvents.Candidate> umbrellaz$explosionAuditCandidates;

    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void umbrellaz$removeProtectedPositions(List<BlockPos> positions, CallbackInfo callbackInfo) {
        ServerLevel level = ((ServerExplosion) (Object) this).level();
        umbrellaz$explosionAuditCandidates = null;
        if (positions == null || positions.isEmpty()) {
            return;
        }
        positions.removeIf(position -> position == null || LockEvents.blocksEnvironmentalMutation(level, position));
        try {
            umbrellaz$explosionAuditCandidates = ExplosionAuditEvents.snapshot(level, positions);
        } catch (RuntimeException ignored) {
            umbrellaz$explosionAuditCandidates = null;
        }
    }

    @Inject(method = "interactWithBlocks", at = @At("RETURN"))
    private void umbrellaz$auditChangedBlocks(List<BlockPos> positions, CallbackInfo callbackInfo) {
        try {
            if (umbrellaz$explosionAuditCandidates != null) {
                ExplosionAuditEvents.record((ServerExplosion) (Object) this, umbrellaz$explosionAuditCandidates);
            }
        } finally {
            umbrellaz$explosionAuditCandidates = null;
        }
    }

}
