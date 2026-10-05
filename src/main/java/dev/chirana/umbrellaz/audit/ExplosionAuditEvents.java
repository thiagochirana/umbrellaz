package dev.chirana.umbrellaz.audit;

import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ServerExplosion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ExplosionAuditEvents {
    private ExplosionAuditEvents() {
    }

    public record Candidate(BlockPos position, BlockState oldState, String oldType) {
    }

    public static List<Candidate> snapshot(ServerLevel level, List<BlockPos> positions) {
        List<Candidate> candidates = new ArrayList<>(Math.min(positions.size(), ExplosionAuditSummary.MAX_CANDIDATES));
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos position : positions) {
            if (position == null || candidates.size() >= ExplosionAuditSummary.MAX_CANDIDATES) {
                break;
            }
            BlockPos immutable = position.immutable();
            if (!seen.add(immutable)) {
                continue;
            }
            BlockState oldState = level.getBlockState(immutable);
            candidates.add(new Candidate(immutable, oldState, blockType(oldState)));
        }
        return List.copyOf(candidates);
    }

    public static void record(ServerExplosion explosion, List<Candidate> candidates) {
        try {
            if (candidates == null || candidates.isEmpty()) {
                return;
            }
            ServerLevel level = explosion.level();
            List<ExplosionAuditSummary.BlockChange> changes = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (candidate == null || candidate.position() == null || candidate.oldState() == null) {
                    continue;
                }
                BlockState current = level.getBlockState(candidate.position());
                if (!candidate.oldState().equals(current)) {
                    changes.add(new ExplosionAuditSummary.BlockChange(candidate.oldType(), blockType(current)));
                }
            }
            if (changes.isEmpty()) {
                return;
            }
            Candidate anchor = candidates.getFirst();
            ExplosionAuditSummary.Summary summary = ExplosionAuditSummary.summarize(
                    new ExplosionAuditSummary.Position(anchor.position().getX(), anchor.position().getY(),
                            anchor.position().getZ()), changes);
            if (summary == null) {
                return;
            }
            String world = level.dimension().identifier().toString();
            AuditPayload payload = AuditPayload.environmentBlocksChanged(world, summary);
            Entity source = explosion.getDamageSource() == null ? null : explosion.getDamageSource().getEntity();
            Actor actor = source instanceof ServerPlayer player
                    ? new Actor(ActorType.PLAYER, player.getUUID(), player.getName().getString())
                    : new Actor(ActorType.SYSTEM, null, "server");
            AuditRecordRequest request = new AuditRecordRequest(
                    AuditRecordContext.forActor(actor), Source.EVENT, AuditActions.ENVIRONMENT_BLOCKS_CHANGED,
                    Outcome.SUCCESS, new Target("environment", "explosion"), "environment_changed", 1, payload,
                    AuditDelivery.BEST_EFFORT);
            ServerRuntimeRegistry.findReady(level.getServer()).ifPresent(runtime -> {
                try {
                    runtime.auditService().record(request).exceptionally(failure -> null);
                } catch (RuntimeException ignored) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private static String blockType(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
