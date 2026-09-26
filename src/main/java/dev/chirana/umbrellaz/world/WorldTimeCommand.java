package dev.chirana.umbrellaz.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class WorldTimeCommand {
    private static final int DAY = 1000;
    private static final int NOON = 6000;
    private static final int NIGHT = 13000;
    private static final int MIDNIGHT = 18000;

    private final AuthorizationService authorizationService;

    public WorldTimeCommand(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("umbrellaz").then(timeCommand("time")));
        dispatcher.register(CommandManager.literal("uz").then(timeCommand("time")));
    }

    private LiteralArgumentBuilder<ServerCommandSource> timeCommand(String name) {
        return CommandManager.literal(name)
                .executes(context -> help(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> help(context.getSource())))
                .then(CommandManager.literal("day").executes(context -> setTime(context.getSource(), DAY, "day")))
                .then(CommandManager.literal("dia").executes(context -> setTime(context.getSource(), DAY, "dia")))
                .then(CommandManager.literal("noon").executes(context -> setTime(context.getSource(), NOON, "noon")))
                .then(CommandManager.literal("meio-dia").executes(context -> setTime(context.getSource(), NOON, "meio-dia")))
                .then(CommandManager.literal("night").executes(context -> setTime(context.getSource(), NIGHT, "night")))
                .then(CommandManager.literal("noite").executes(context -> setTime(context.getSource(), NIGHT, "noite")))
                .then(CommandManager.literal("midnight").executes(context -> setTime(context.getSource(), MIDNIGHT, "midnight")))
                .then(CommandManager.literal("meia-noite").executes(context -> setTime(context.getSource(), MIDNIGHT, "meia-noite")))
                .then(CommandManager.argument("time", IntegerArgumentType.integer(0))
                        .executes(context -> setTime(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "time"),
                                "valor personalizado")));
    }

    private int setTime(ServerCommandSource source, int time, String label) {
        if (!authorized(source)) {
            source.sendError(error("Você não tem autorização Umbrellaz para alterar o tempo."));
            return 0;
        }
        for (ServerWorld world : source.getServer().getWorlds()) {
            world.setTimeOfDay(time);
        }
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(Text.literal("Tempo definido: ").formatted(Formatting.GREEN))
                .append(Text.literal(label).formatted(Formatting.YELLOW, Formatting.BOLD))
                .append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(Integer.toString(time) + " ticks").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" em todos os mundos.").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private boolean authorized(ServerCommandSource source) {
        return source.getEntity() == null
                || source.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity player
                && authorizationService.isAdministrator(player.getUuid());
    }

    private int help(ServerCommandSource source) {
        source.sendFeedback(() -> header("Tempo · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz time day", "Define o período diurno Vanilla (1000 ticks)"), false);
        source.sendFeedback(() -> helpLine("/uz time dia", "Atalho em português para day"), false);
        source.sendFeedback(() -> helpLine("/uz time noon", "Define o meio-dia (6000 ticks)"), false);
        source.sendFeedback(() -> helpLine("/uz time meio-dia", "Atalho em português para noon"), false);
        source.sendFeedback(() -> helpLine("/uz time night", "Define a noite Vanilla (13000 ticks)"), false);
        source.sendFeedback(() -> helpLine("/uz time noite", "Atalho em português para night"), false);
        source.sendFeedback(() -> helpLine("/uz time midnight", "Define meia-noite (18000 ticks)"), false);
        source.sendFeedback(() -> helpLine("/uz time meia-noite", "Atalho em português para midnight"), false);
        source.sendFeedback(() -> helpLine("/uz time <número>", "Define um valor inteiro de ticks"), false);
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

    private Text successPrefix() {
        return Text.literal("✓ ").formatted(Formatting.GREEN, Formatting.BOLD);
    }

    private Text error(String message) {
        return Text.empty()
                .append(Text.literal("✕ ").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(message).formatted(Formatting.RED));
    }
}
