package dev.chirana.umbrellaz.teleport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class TeleportCommand {
    private final AuthorizationService authorizationService;

    public TeleportCommand(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("umbrellaz")
                .then(teleportCommand("tp"))
                .then(teleportCommand("teleport")));
        dispatcher.register(CommandManager.literal("uz")
                .then(teleportCommand("tp"))
                .then(teleportCommand("teleport")));
    }

    private LiteralArgumentBuilder<ServerCommandSource> teleportCommand(String name) {
        return CommandManager.literal(name)
                .executes(context -> help(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> help(context.getSource())))
                .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> teleportSelf(
                                context.getSource(), StringArgumentType.getString(context, "player")))
                        .then(CommandManager.argument("target", StringArgumentType.word())
                                .executes(context -> teleportPlayerToPlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "player"),
                                        StringArgumentType.getString(context, "target"))))
                        .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                        .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> teleportPlayerToCoordinates(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "player"),
                                                        DoubleArgumentType.getDouble(context, "x"),
                                                        DoubleArgumentType.getDouble(context, "y"),
                                                        DoubleArgumentType.getDouble(context, "z")))))));
    }

    private int teleportSelf(ServerCommandSource source, String destinationName) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        ServerPlayerEntity executor = source.getPlayer();
        if (executor == null) {
            source.sendError(error("Este formato precisa ser executado por um jogador."));
            return 0;
        }
        ServerPlayerEntity destination = findPlayer(source.getServer(), destinationName);
        if (destination == null) {
            unknownPlayer(source, destinationName);
            return 0;
        }
        move(executor, destination);
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(Text.literal("Você foi teleportado até ").formatted(Formatting.GREEN))
                .append(playerName(destinationName))
                .append(Text.literal(".").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private int teleportPlayerToPlayer(ServerCommandSource source, String playerName, String targetName) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        ServerPlayerEntity player = findPlayer(source.getServer(), playerName);
        ServerPlayerEntity target = findPlayer(source.getServer(), targetName);
        if (player == null) {
            unknownPlayer(source, playerName);
            return 0;
        }
        if (target == null) {
            unknownPlayer(source, targetName);
            return 0;
        }
        move(player, target);
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(playerName(playerName))
                .append(Text.literal(" foi teleportado até ").formatted(Formatting.GREEN))
                .append(playerName(targetName))
                .append(Text.literal(".").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private int teleportPlayerToCoordinates(ServerCommandSource source, String playerName, double x, double y, double z) {
        if (!authorized(source)) {
            deny(source);
            return 0;
        }
        ServerPlayerEntity player = findPlayer(source.getServer(), playerName);
        if (player == null) {
            unknownPlayer(source, playerName);
            return 0;
        }
        ServerWorld world = source.getWorld();
        if (world == null) {
            world = source.getServer().getOverworld();
        }
        player.teleport(world, x, y, z, player.getYaw(), player.getPitch());
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(playerName(playerName))
                .append(Text.literal(" foi teleportado para ").formatted(Formatting.GREEN))
                .append(coordinates(x, y, z))
                .append(Text.literal(".").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private boolean authorized(ServerCommandSource source) {
        return source.getEntity() == null
                || source.getEntity() instanceof ServerPlayerEntity player
                && authorizationService.isAdministrator(player.getUuid());
    }

    private ServerPlayerEntity findPlayer(MinecraftServer server, String name) {
        return server.getPlayerManager().getPlayer(name);
    }

    private void move(ServerPlayerEntity player, ServerPlayerEntity target) {
        player.teleport(target.getServerWorld(), target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
    }

    private void unknownPlayer(ServerCommandSource source, String name) {
        source.sendError(Text.empty()
                .append(errorPrefix())
                .append(Text.literal("Jogador online não encontrado: ").formatted(Formatting.RED))
                .append(playerName(name)));
    }

    private void deny(ServerCommandSource source) {
        source.sendError(error("Você não tem autorização Umbrellaz para teleportar jogadores."));
    }

    private int help(ServerCommandSource source) {
        source.sendFeedback(() -> header("Teleporte · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz tp <jogador>", "Teleporta você até o jogador"), false);
        source.sendFeedback(() -> helpLine("/uz tp <jogador> <alvo>", "Move um jogador até outro"), false);
        source.sendFeedback(() -> helpLine("/uz tp <jogador> <x> <y> <z>", "Move um jogador para as coordenadas"), false);
        return 1;
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

    private Text playerName(String name) {
        return Text.literal(name).formatted(Formatting.AQUA, Formatting.BOLD);
    }

    private Text coordinates(double x, double y, double z) {
        return Text.literal(format(x) + ", " + format(y) + ", " + format(z))
                .formatted(Formatting.GOLD, Formatting.BOLD);
    }

    private Text successPrefix() {
        return Text.literal("✓ ").formatted(Formatting.GREEN, Formatting.BOLD);
    }

    private Text errorPrefix() {
        return Text.literal("✕ ").formatted(Formatting.RED, Formatting.BOLD);
    }

    private Text error(String message) {
        return Text.empty()
                .append(errorPrefix())
                .append(Text.literal(message).formatted(Formatting.RED));
    }

    private String format(double coordinate) {
        return Double.toString(coordinate);
    }
}
