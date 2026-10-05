package dev.chirana.umbrellaz.blocks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlocksCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlocksCommand.class);
    private final BlocksService blocksService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public BlocksCommand(BlocksService blocksService, AuthorizationService authorizationService) {
        this(blocksService, authorizationService, null);
    }

    public BlocksCommand(BlocksService blocksService, AuthorizationService authorizationService,
                         AuditService auditService) {
        this.blocksService = blocksService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz").then(blocksCommand()));
        dispatcher.register(Commands.literal("uz").then(blocksCommand()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> blocksCommand() {
        return Commands.literal("blocks")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(modeCommand("tree", true))
                .then(modeCommand("ores", false));
    }

    private LiteralArgumentBuilder<CommandSourceStack> modeCommand(String name, boolean tree) {
        return Commands.literal(name)
                .executes(context -> status(context.getSource(), tree))
                .then(Commands.literal("help").executes(context -> modeHelp(context.getSource(), tree)))
                .then(Commands.literal("ezbreak")
                        .executes(context -> status(context.getSource(), tree))
                        .then(Commands.literal("on")
                                .executes(context -> set(context.getSource(), tree, true)))
                        .then(Commands.literal("off")
                                .executes(context -> set(context.getSource(), tree, false))));
    }

    private int set(CommandSourceStack source, boolean tree, boolean enabled) {
        AuditRecordContext auditContext = auditContext(source);
        if (!authorized(source)) {
            deny(source, auditContext, tree ? "blocks.tree.ezbreak" : "blocks.ores.ezbreak");
            return 0;
        }
        if (tree) {
            blocksService.setTreeEzBreakEnabled(enabled);
        } else {
            blocksService.setOresEzBreakEnabled(enabled);
        }
        emit(auditContext, AuditActions.BLOCKS_CONFIGURATION_SET, Outcome.SUCCESS,
                AuditPayload.forAction(AuditActions.BLOCKS_CONFIGURATION_SET,
                        AuditPayload.configuration(tree ? "tree.ezbreak" : "ores.ezbreak"),
                        AuditPayload.enabled(enabled), AuditPayload.result("success")));
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal(tree ? "Automação de árvores " : "Automação de minérios ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(enabled ? "ativado" : "desativado").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int status(CommandSourceStack source, boolean tree) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        boolean enabled = tree ? blocksService.treeEzBreakEnabled() : blocksService.oresEzBreakEnabled();
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("◆ ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(tree ? "Automação de árvores: " : "Automação de minérios: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(enabled ? "ativado" : "desativado")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD)), false);
        return 1;
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Blocos · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks tree ezbreak on", "Ativa a cascata ao quebrar segurando Shift"), false);
        source.sendSuccess(() -> helpLine("/uz blocks tree ezbreak off", "Desativa a cascata de árvores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks ores ezbreak on", "Ativa a cascata ao quebrar segurando Shift"), false);
        source.sendSuccess(() -> helpLine("/uz blocks ores ezbreak off", "Desativa a cascata de minérios"), false);
        return 1;
    }

    private int modeHelp(CommandSourceStack source, boolean tree) {
        source.sendSuccess(() -> header(tree ? "Árvores · administradores" : "Minérios · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks " + (tree ? "tree" : "ores") + " ezbreak on", "Ativa a cascata ao quebrar segurando Shift"), false);
        source.sendSuccess(() -> helpLine("/uz blocks " + (tree ? "tree" : "ores") + " ezbreak off", "Desativa a cascata; a quebra normal continua"), false);
        return 1;
    }

    private boolean authorized(CommandSourceStack source) {
        return CommandAuthorization.isAdministrator(source, authorizationService);
    }

    private void deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para alterar a automação de blocos."));
    }

    private void deny(CommandSourceStack source, AuditRecordContext context, String permission) {
        deny(source);
        emit(context, AuditActions.AUTHORIZATION_COMMAND_DENIED, Outcome.DENIED,
                AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                        AuditPayload.permission(permission), AuditPayload.reasonCode("not_administrator")));
    }

    private AuditRecordContext auditContext(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                    player.getName().getString()));
        }
        return AuditRecordContext.forActor(new Actor(ActorType.CONSOLE, null, null));
    }

    private void emit(AuditRecordContext context, String action, Outcome outcome, AuditPayload payload) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.record(new AuditRecordRequest(context, Source.COMMAND, action, outcome, null, null, 1,
                    payload, AuditDelivery.BEST_EFFORT)).exceptionally(failure -> {
                LOGGER.warn("Unable to record blocks command audit event", failure);
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create blocks command audit event", failure);
        }
    }

    private Component header(String section) {
        return Component.empty()
                .append(Component.literal("◆ UMBRELLAZ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("  " + section).withStyle(ChatFormatting.YELLOW));
    }

    private Component helpLine(String command, String description) {
        return Component.empty()
                .append(Component.literal("  " + command).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  —  " + description).withStyle(ChatFormatting.GRAY));
    }

    private Component successPrefix() {
        return Component.literal("✓ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
    }

    private Component error(String message) {
        return Component.empty()
                .append(Component.literal("✕ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
