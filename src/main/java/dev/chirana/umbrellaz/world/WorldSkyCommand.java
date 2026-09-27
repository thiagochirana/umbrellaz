package dev.chirana.umbrellaz.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class WorldSkyCommand {
    private final AuthorizationService authorizationService;
    private final WorldSkyService skyService;

    public WorldSkyCommand(AuthorizationService authorizationService, WorldSkyService skyService) {
        this.authorizationService = authorizationService;
        this.skyService = skyService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz").then(skyCommand("sky")));
        dispatcher.register(Commands.literal("uz").then(skyCommand("sky")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> skyCommand(String name) {
        return Commands.literal(name)
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("clean").executes(context -> apply(context.getSource(), WorldSkyMode.CLEAN)))
                .then(Commands.literal("rain").executes(context -> apply(context.getSource(), WorldSkyMode.RAIN)))
                .then(Commands.literal("storm").executes(context -> apply(context.getSource(), WorldSkyMode.STORM)));
    }

    private int apply(CommandSourceStack source, WorldSkyMode mode) {
        if (!CommandAuthorization.isAdministrator(source, authorizationService)) {
            source.sendFailure(error("Você não tem autorização Umbrellaz para alterar o clima."));
            return 0;
        }
        skyService.apply(source.getServer(), mode);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("Clima definido: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(mode.displayName()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" em todos os mundos.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Clima · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz sky clean", "Limpa a chuva e os trovões"), false);
        source.sendSuccess(() -> helpLine("/uz sky rain", "Ativa a chuva sem trovões"), false);
        source.sendSuccess(() -> helpLine("/uz sky storm", "Ativa chuva e trovões"), false);
        return 1;
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
