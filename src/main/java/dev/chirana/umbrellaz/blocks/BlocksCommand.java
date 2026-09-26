package dev.chirana.umbrellaz.blocks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class BlocksCommand {
    private final BlocksService blocksService;
    private final AuthorizationService authorizationService;

    public BlocksCommand(BlocksService blocksService, AuthorizationService authorizationService) {
        this.blocksService = blocksService;
        this.authorizationService = authorizationService;
    }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("umbrellaz").then(blocksCommand()));
        dispatcher.register(CommandManager.literal("uz").then(blocksCommand()));
    }

    private LiteralArgumentBuilder<ServerCommandSource> blocksCommand() {
        return CommandManager.literal("blocks")
                .executes(context -> help(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> help(context.getSource())))
                .then(modeCommand("tree", true))
                .then(modeCommand("ores", false));
    }

    private LiteralArgumentBuilder<ServerCommandSource> modeCommand(String name, boolean tree) {
        return CommandManager.literal(name)
                .executes(context -> status(context.getSource(), tree))
                .then(CommandManager.literal("help").executes(context -> modeHelp(context.getSource(), tree)))
                .then(CommandManager.literal("ezbreak")
                        .executes(context -> status(context.getSource(), tree))
                        .then(CommandManager.literal("on")
                                .executes(context -> set(context.getSource(), tree, true)))
                        .then(CommandManager.literal("off")
                                .executes(context -> set(context.getSource(), tree, false))));
    }

    private int set(ServerCommandSource source, boolean tree, boolean enabled) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        if (tree) {
            blocksService.setTreeEzBreakEnabled(enabled);
        } else {
            blocksService.setOresEzBreakEnabled(enabled);
        }
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(Text.literal(tree ? "Tree ezbreak " : "Ores ezbreak ").formatted(Formatting.GREEN))
                .append(Text.literal(enabled ? "ativado" : "desativado").formatted(Formatting.YELLOW, Formatting.BOLD))
                .append(Text.literal(".").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private int status(ServerCommandSource source, boolean tree) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        boolean enabled = tree ? blocksService.treeEzBreakEnabled() : blocksService.oresEzBreakEnabled();
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("◆ ").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(tree ? "Tree ezbreak: " : "Ores ezbreak: ").formatted(Formatting.YELLOW))
                .append(Text.literal(enabled ? "ativado" : "desativado")
                        .formatted(enabled ? Formatting.GREEN : Formatting.RED, Formatting.BOLD)), false);
        return 1;
    }

    private int help(ServerCommandSource source) {
        source.sendFeedback(() -> header("Blocos · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz blocks tree ezbreak on", "Ativa a quebra automática de árvores"), false);
        source.sendFeedback(() -> helpLine("/uz blocks tree ezbreak off", "Desativa a quebra automática de árvores"), false);
        source.sendFeedback(() -> helpLine("/uz blocks ores ezbreak on", "Ativa a quebra automática de minérios conectados"), false);
        source.sendFeedback(() -> helpLine("/uz blocks ores ezbreak off", "Desativa a quebra automática de minérios conectados"), false);
        return 1;
    }

    private int modeHelp(ServerCommandSource source, boolean tree) {
        source.sendFeedback(() -> header(tree ? "Árvores · administradores" : "Minérios · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz blocks " + (tree ? "tree" : "ores") + " ezbreak on", "Ativa o ezbreak"), false);
        source.sendFeedback(() -> helpLine("/uz blocks " + (tree ? "tree" : "ores") + " ezbreak off", "Desativa o ezbreak"), false);
        return 1;
    }

    private boolean authorized(ServerCommandSource source) {
        return source.getEntity() == null
                || source.getEntity() instanceof ServerPlayerEntity player
                && authorizationService.isAdministrator(player.getUuid());
    }

    private void deny(ServerCommandSource source) {
        source.sendError(error("Você não tem autorização Umbrellaz para alterar a automação de blocos."));
    }

    private Text header(String section) {
        return Text.empty()
                .append(Text.literal("◆ UMBRELLAZ").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("  " + section).formatted(Formatting.YELLOW));
    }

    private Text helpLine(String command, String description) {
        return Text.empty()
                .append(Text.literal("  " + command).formatted(Formatting.YELLOW))
                .append(Text.literal("  —  " + description).formatted(Formatting.GRAY));
    }

    private Text successPrefix() {
        return Text.literal("✓ ").formatted(Formatting.GREEN, Formatting.BOLD);
    }

    private Text error(String message) {
        return Text.empty()
                .append(Text.literal("✕ ").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(message).formatted(Formatting.RED));
    }
}
