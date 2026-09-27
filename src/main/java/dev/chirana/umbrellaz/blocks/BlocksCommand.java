package dev.chirana.umbrellaz.blocks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class BlocksCommand {
    private final BlocksService blocksService;
    private final AuthorizationService authorizationService;

    public BlocksCommand(BlocksService blocksService, AuthorizationService authorizationService) {
        this.blocksService = blocksService;
        this.authorizationService = authorizationService;
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
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        if (tree) {
            blocksService.setTreeEzBreakEnabled(enabled);
        } else {
            blocksService.setOresEzBreakEnabled(enabled);
        }
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal(tree ? "Tree ezbreak " : "Ores ezbreak ").withStyle(ChatFormatting.GREEN))
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
                .append(Component.literal(tree ? "Tree ezbreak: " : "Ores ezbreak: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(enabled ? "ativado" : "desativado")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD)), false);
        return 1;
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Blocos · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks tree ezbreak on", "Ativa a quebra automática de árvores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks tree ezbreak off", "Desativa a quebra automática de árvores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks ores ezbreak on", "Ativa a quebra automática de minérios conectados"), false);
        source.sendSuccess(() -> helpLine("/uz blocks ores ezbreak off", "Desativa a quebra automática de minérios conectados"), false);
        return 1;
    }

    private int modeHelp(CommandSourceStack source, boolean tree) {
        source.sendSuccess(() -> header(tree ? "Árvores · administradores" : "Minérios · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz blocks " + (tree ? "tree" : "ores") + " ezbreak on", "Ativa o ezbreak"), false);
        source.sendSuccess(() -> helpLine("/uz blocks " + (tree ? "tree" : "ores") + " ezbreak off", "Desativa o ezbreak"), false);
        return 1;
    }

    private boolean authorized(CommandSourceStack source) {
        return source.getEntity() == null
                || source.getEntity() instanceof ServerPlayer player
                && authorizationService.isAdministrator(player.getUUID());
    }

    private void deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para alterar a automação de blocos."));
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
