package dev.chirana.umbrellaz.teleport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import dev.chirana.umbrellaz.player.OnlinePlayerResolver;
import dev.chirana.umbrellaz.player.OnlinePlayerSuggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class TeleportCommand {
    private final AuthorizationService authorizationService;
    private final OnlinePlayerResolver playerResolver;
    private final OnlinePlayerSuggestions playerSuggestions;

    public TeleportCommand(AuthorizationService authorizationService) {
        this(authorizationService, new OnlinePlayerResolver(), new OnlinePlayerSuggestions());
    }

    public TeleportCommand(AuthorizationService authorizationService, OnlinePlayerResolver playerResolver) {
        this(authorizationService, playerResolver, new OnlinePlayerSuggestions());
    }

    public TeleportCommand(AuthorizationService authorizationService, OnlinePlayerResolver playerResolver,
                           OnlinePlayerSuggestions playerSuggestions) {
        this.authorizationService = authorizationService;
        this.playerResolver = playerResolver;
        this.playerSuggestions = playerSuggestions;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz")
                .then(teleportCommand("tp"))
                .then(teleportCommand("teleport")));
        dispatcher.register(Commands.literal("uz")
                .then(teleportCommand("tp"))
                .then(teleportCommand("teleport")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> teleportCommand(String name) {
        return Commands.literal(name)
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(playerSuggestions)
                        .executes(context -> teleportSelf(
                                context.getSource(), StringArgumentType.getString(context, "player")))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(playerSuggestions)
                                .executes(context -> teleportPlayerToPlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "player"),
                                        StringArgumentType.getString(context, "target"))))
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> teleportPlayerToCoordinates(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "player"),
                                                        DoubleArgumentType.getDouble(context, "x"),
                                                        DoubleArgumentType.getDouble(context, "y"),
                                                        DoubleArgumentType.getDouble(context, "z")))))));
    }

    private int teleportSelf(CommandSourceStack source, String destinationName) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        ServerPlayer executor = source.getPlayer();
        if (executor == null) {
            source.sendFailure(error("Este formato precisa ser executado por um jogador."));
            return 0;
        }
        var destinationResolution = findPlayer(source.getServer(), destinationName);
        if (destinationResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            reportResolution(source, destinationName, destinationResolution.status());
            return 0;
        }
        ServerPlayer destination = destinationResolution.player();
        move(executor, destination);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("Você foi teleportado até ").withStyle(ChatFormatting.GREEN))
                .append(playerName(destinationName))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int teleportPlayerToPlayer(CommandSourceStack source, String playerName, String targetName) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        var playerResolution = findPlayer(source.getServer(), playerName);
        if (playerResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            reportResolution(source, playerName, playerResolution.status());
            return 0;
        }
        var targetResolution = findPlayer(source.getServer(), targetName);
        if (targetResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            reportResolution(source, targetName, targetResolution.status());
            return 0;
        }
        ServerPlayer player = playerResolution.player();
        ServerPlayer target = targetResolution.player();
        move(player, target);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(playerName))
                .append(Component.literal(" foi teleportado até ").withStyle(ChatFormatting.GREEN))
                .append(playerName(targetName))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int teleportPlayerToCoordinates(CommandSourceStack source, String playerName, double x, double y, double z) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        var playerResolution = findPlayer(source.getServer(), playerName);
        if (playerResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            reportResolution(source, playerName, playerResolution.status());
            return 0;
        }
        ServerPlayer player = playerResolution.player();
        ServerLevel world = source.getLevel();
        if (world == null) {
            world = source.getServer().overworld();
        }
        player.teleportTo(world, x, y, z, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(playerName))
                .append(Component.literal(" foi teleportado para ").withStyle(ChatFormatting.GREEN))
                .append(coordinates(x, y, z))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private boolean authorized(CommandSourceStack source) {
        return CommandAuthorization.isAdministrator(source, authorizationService);
    }

    private dev.chirana.umbrellaz.player.OnlinePlayerResolution findPlayer(MinecraftServer server, String name) {
        return playerResolver.resolve(server, name);
    }

    private void move(ServerPlayer player, ServerPlayer target) {
        player.teleportTo(target.level(), target.getX(), target.getY(), target.getZ(), java.util.Set.of(), target.getYRot(), target.getXRot(), true);
    }

    private void unknownPlayer(CommandSourceStack source, String name) {
        source.sendFailure(Component.empty()
                .append(errorPrefix())
                .append(Component.literal("Jogador online não encontrado: ").withStyle(ChatFormatting.RED))
                .append(playerName(name)));
    }

    private void reportResolution(CommandSourceStack source, String name,
                                  dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status status) {
        String message = switch (status) {
            case AMBIGUOUS -> "Identificador ambíguo: ";
            case NOT_READY -> "A resolução de aliases ainda não está pronta: ";
            case NOT_FOUND -> "Jogador online não encontrado: ";
            case FOUND -> "";
        };
        source.sendFailure(Component.empty()
                .append(errorPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.RED))
                .append(playerName(name)));
    }

    private void deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para teleportar jogadores."));
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Teleporte · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz tp <jogador>", "Teleporta você até o jogador"), false);
        source.sendSuccess(() -> helpLine("/uz tp <jogador> <alvo>", "Move um jogador até outro"), false);
        source.sendSuccess(() -> helpLine("/uz tp <jogador> <x> <y> <z>", "Move um jogador para as coordenadas"), false);
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

    private Component playerName(String name) {
        return Component.literal(name).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private Component coordinates(double x, double y, double z) {
        return Component.literal(format(x) + ", " + format(y) + ", " + format(z))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private Component successPrefix() {
        return Component.literal("✓ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
    }

    private Component errorPrefix() {
        return Component.literal("✕ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    private Component error(String message) {
        return Component.empty()
                .append(errorPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private String format(double coordinate) {
        return Double.toString(coordinate);
    }
}
