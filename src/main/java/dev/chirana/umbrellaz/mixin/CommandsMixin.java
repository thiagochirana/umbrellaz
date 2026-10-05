package dev.chirana.umbrellaz.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
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
import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    private static final String UNKNOWN = "unknown";
    private static final String RETURNED_STATUS = "returned";
    private static final String RETURNED_RESULT_UNAVAILABLE = "result_unavailable";

    @Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V", at = @At("RETURN"))
    private void umbrellaz$auditCommand(ParseResults<CommandSourceStack> parseResults, String command,
                                         CallbackInfo callbackInfo) {
        recordCommand(parseResults);
    }

    private static void recordCommand(ParseResults<CommandSourceStack> parseResults) {
        try {
            if (parseResults == null) {
                return;
            }
            CommandContextBuilder<CommandSourceStack> context = parseResults.getContext();
            if (context == null || context.getSource() == null) {
                return;
            }

            CommandSourceStack source = context.getSource();
            ServerPlayer player = source.getPlayer();
            Actor actor;
            String sourceKind;
            if (player != null) {
                actor = new Actor(ActorType.PLAYER, player.getUUID(), player.getName().getString());
                sourceKind = ActorType.PLAYER.code();
            } else if (source.getEntity() == null) {
                actor = new Actor(ActorType.CONSOLE, null, null);
                sourceKind = ActorType.CONSOLE.code();
            } else {
                actor = new Actor(ActorType.SYSTEM, null, null);
                sourceKind = ActorType.SYSTEM.code();
            }

            ServerRuntimeRegistry.findReady(source.getServer()).ifPresent(runtime -> {
                AuditService auditService = runtime.auditService();
                AuditPayload payload = AuditPayload.forAction(AuditActions.COMMAND_EXECUTED,
                        AuditPayload.commandPath(commandPath(context)),
                        AuditPayload.commandSourceKind(sourceKind),
                        AuditPayload.commandStatus(RETURNED_STATUS));
                AuditRecordRequest request = new AuditRecordRequest(
                        AuditRecordContext.forActor(actor), Source.COMMAND, AuditActions.COMMAND_EXECUTED,
                        new Outcome("completed"), null, RETURNED_RESULT_UNAVAILABLE, 1, payload,
                        AuditDelivery.BEST_EFFORT);
                try {
                    auditService.record(request).whenComplete((ignored, failure) -> {
                        // BEST_EFFORT audit failures must not affect command execution.
                    });
                } catch (Throwable ignored) {
                    // Audit is deliberately isolated from the command path.
                }
            });
        } catch (Throwable ignored) {
            // A malformed or unavailable source must never change command behavior.
        }
    }

    private static String commandPath(CommandContextBuilder<CommandSourceStack> context) {
        List<String> literalNames = new ArrayList<>();
        for (ParsedCommandNode<CommandSourceStack> parsedNode : context.getNodes()) {
            if (parsedNode == null) {
                return UNKNOWN;
            }
            CommandNode<CommandSourceStack> node = parsedNode.getNode();
            if (node instanceof LiteralCommandNode<CommandSourceStack> literal) {
                literalNames.add(literal.getLiteral());
            }
        }
        return CommandAuditPath.bounded(literalNames);
    }
}
