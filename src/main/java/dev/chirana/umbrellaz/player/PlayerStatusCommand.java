package dev.chirana.umbrellaz.player;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class PlayerStatusCommand {
    private final AuthorizationService authorizationService;
    private final HealthLockService healthLockService;

    public PlayerStatusCommand(AuthorizationService authorizationService, HealthLockService healthLockService) {
        this.authorizationService = authorizationService;
        this.healthLockService = healthLockService;
    }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("umbrellaz")
                .then(healthCommand("hp"))
                .then(experienceCommand("xp"))
                .then(killCommand()));
        dispatcher.register(CommandManager.literal("uz")
                .then(healthCommand("hp"))
                .then(experienceCommand("xp"))
                .then(killCommand()));
    }

    private LiteralArgumentBuilder<ServerCommandSource> healthCommand(String name) {
        return CommandManager.literal(name)
                .executes(context -> healthHelp(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> healthHelp(context.getSource())))
                .then(selfHealthCommand())
                .then(healthPlayerCommand());
    }

    private LiteralArgumentBuilder<ServerCommandSource> selfHealthCommand() {
        return CommandManager.literal("self")
                .executes(context -> showHealth(context.getSource(), context.getSource().getPlayer(), "self"))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> addHealth(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        DoubleArgumentType.getDouble(context, "amount"),
                                        "self"))))
                .then(CommandManager.literal("rm")
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> removeHealth(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        DoubleArgumentType.getDouble(context, "amount"),
                                        "self"))))
                .then(CommandManager.literal("lock")
                        .executes(context -> lockHealth(context.getSource(), context.getSource().getPlayer(), "self")))
                .then(CommandManager.literal("unlock")
                        .executes(context -> unlockHealth(context.getSource(), context.getSource().getPlayer(), "self")));
    }

    private RequiredArgumentBuilder<ServerCommandSource, String> healthPlayerCommand() {
        return CommandManager.argument("player", StringArgumentType.word())
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "player");
                    return showHealth(context.getSource(), findPlayer(context.getSource(), name), name);
                })
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return addHealth(context.getSource(), findPlayer(context.getSource(), name),
                                            DoubleArgumentType.getDouble(context, "amount"), name);
                                })))
                .then(CommandManager.literal("rm")
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return removeHealth(context.getSource(), findPlayer(context.getSource(), name),
                                            DoubleArgumentType.getDouble(context, "amount"), name);
                                })))
                .then(CommandManager.literal("lock")
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            return lockHealth(context.getSource(), findPlayer(context.getSource(), name), name);
                        }))
                .then(CommandManager.literal("unlock")
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            return unlockHealth(context.getSource(), findPlayer(context.getSource(), name), name);
                        }));
    }

    private LiteralArgumentBuilder<ServerCommandSource> experienceCommand(String name) {
        return CommandManager.literal(name)
                .executes(context -> experienceHelp(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> experienceHelp(context.getSource())))
                .then(selfExperienceCommand())
                .then(experiencePlayerCommand());
    }

    private LiteralArgumentBuilder<ServerCommandSource> killCommand() {
        return CommandManager.literal("kill")
                .executes(context -> killHelp(context.getSource()))
                .then(CommandManager.literal("help").executes(context -> killHelp(context.getSource())))
                .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> killPlayer(
                                context.getSource(),
                                StringArgumentType.getString(context, "player"))));
    }

    private LiteralArgumentBuilder<ServerCommandSource> selfExperienceCommand() {
        return CommandManager.literal("self")
                .executes(context -> showExperience(context.getSource(), context.getSource().getPlayer(), "self"))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> addExperience(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                        "self"))))
                .then(CommandManager.literal("rm")
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> removeExperience(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                        "self"))));
    }

    private RequiredArgumentBuilder<ServerCommandSource, String> experiencePlayerCommand() {
        return CommandManager.argument("player", StringArgumentType.word())
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "player");
                    return showExperience(context.getSource(), findPlayer(context.getSource(), name), name);
                })
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return addExperience(context.getSource(), findPlayer(context.getSource(), name),
                                            IntegerArgumentType.getInteger(context, "amount"), name);
                                })))
                .then(CommandManager.literal("rm")
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return removeExperience(context.getSource(), findPlayer(context.getSource(), name),
                                            IntegerArgumentType.getInteger(context, "amount"), name);
                                })));
    }

    private int showHealth(ServerCommandSource source, ServerPlayerEntity player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("♥ ").formatted(Formatting.RED, Formatting.BOLD))
                .append(playerName(player))
                .append(Text.literal("  HP  ").formatted(Formatting.GRAY))
                .append(healthValue(player.getHealth(), player.getMaxHealth())), false);
        return 1;
    }

    private int addHealth(ServerCommandSource source, ServerPlayerEntity player, double amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        float current = player.getHealth();
        float next = Math.min(player.getMaxHealth(), current + (float) amount);
        player.setHealth(next);
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Text.literal("  HP alterado para ").formatted(Formatting.GREEN))
                .append(healthValue(next, player.getMaxHealth())), true);
        return 1;
    }

    private int lockHealth(ServerCommandSource source, ServerPlayerEntity player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            if (name.equals("self")) {
                source.sendError(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        float lockedHealth = Math.max(1.0f, Math.min(player.getMaxHealth(), player.getHealth()));
        healthLockService.lock(player.getUuid(), lockedHealth);
        player.setHealth(lockedHealth);
        source.sendFeedback(() -> Text.empty()
                .append(warningPrefix())
                .append(playerName(player))
                .append(Text.literal("  HP travado em ").formatted(Formatting.YELLOW))
                .append(Text.literal(format(lockedHealth)).formatted(Formatting.RED, Formatting.BOLD)), true);
        return 1;
    }

    private int unlockHealth(ServerCommandSource source, ServerPlayerEntity player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            if (name.equals("self")) {
                source.sendError(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        healthLockService.unlock(player.getUuid());
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Text.literal("  HP destravado.").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private int removeHealth(ServerCommandSource source, ServerPlayerEntity player, double amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        float current = player.getHealth();
        float next = Math.max(1.0f, current - (float) amount);
        player.setHealth(next);
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Text.literal("  HP alterado para ").formatted(Formatting.GREEN))
                .append(healthValue(next, player.getMaxHealth())), true);
        return 1;
    }

    private int showExperience(ServerCommandSource source, ServerPlayerEntity player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("✦ ").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(playerName(player))
                .append(Text.literal("  XP  ").formatted(Formatting.GRAY))
                .append(Text.literal(Integer.toString(player.totalExperience)).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" pontos  ·  nível ").formatted(Formatting.GRAY))
                .append(Text.literal(Integer.toString(player.experienceLevel)).formatted(Formatting.GOLD, Formatting.BOLD)), false);
        return 1;
    }

    private int addExperience(ServerCommandSource source, ServerPlayerEntity player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        player.addExperience(amount);
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(Text.literal("+" + amount + " XP").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" para ").formatted(Formatting.GREEN))
                .append(playerName(player))
                .append(Text.literal("  ·  Total ").formatted(Formatting.GRAY))
                .append(Text.literal(Integer.toString(player.totalExperience)).formatted(Formatting.GREEN, Formatting.BOLD)), true);
        return 1;
    }

    private int removeExperience(ServerCommandSource source, ServerPlayerEntity player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        player.addExperience(-amount);
        source.sendFeedback(() -> Text.empty()
                .append(successPrefix())
                .append(Text.literal("−" + amount + " XP").formatted(Formatting.YELLOW, Formatting.BOLD))
                .append(Text.literal(" de ").formatted(Formatting.GREEN))
                .append(playerName(player))
                .append(Text.literal("  ·  Total ").formatted(Formatting.GRAY))
                .append(Text.literal(Integer.toString(player.totalExperience)).formatted(Formatting.GREEN, Formatting.BOLD)), true);
        return 1;
    }

    private int killPlayer(ServerCommandSource source, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        ServerPlayerEntity player = findPlayer(source, name);
        if (player == null) {
            return unknownPlayer(source, name);
        }
        healthLockService.unlock(player.getUuid());
        player.sendMessage(Text.literal("Admin matou você, seu boboca 😈")
                .formatted(Formatting.DARK_RED, Formatting.BOLD), false);
        player.kill();
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("☠ ").formatted(Formatting.RED, Formatting.BOLD))
                .append(playerName(player))
                .append(Text.literal(" foi morto.").formatted(Formatting.RED)), true);
        return 1;
    }

    private ServerPlayerEntity findPlayer(ServerCommandSource source, String name) {
        if (name.equalsIgnoreCase("self")) {
            return source.getPlayer();
        }
        return source.getServer().getPlayerManager().getPlayer(name);
    }

    private boolean authorized(ServerCommandSource source) {
        return source.getEntity() == null
                || source.getEntity() instanceof ServerPlayerEntity player
                && authorizationService.isAdministrator(player.getUuid());
    }

    private int deny(ServerCommandSource source) {
        source.sendError(error("Você não tem autorização Umbrellaz para usar este comando."));
        return 0;
    }

    private int unknownPlayer(ServerCommandSource source, String name) {
        source.sendError(Text.empty()
                .append(errorPrefix())
                .append(Text.literal("Jogador online não encontrado: ").formatted(Formatting.RED))
                .append(playerName(name)));
        return 0;
    }

    private int healthHelp(ServerCommandSource source) {
        source.sendFeedback(() -> header("HP · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz hp <jogador>", "Mostra o HP atual e máximo"), false);
        source.sendFeedback(() -> helpLine("/uz hp <jogador> add <pontos>", "Adiciona HP até o máximo"), false);
        source.sendFeedback(() -> helpLine("/uz hp <jogador> rm <pontos>", "Remove HP sem chegar abaixo de 1"), false);
        source.sendFeedback(() -> helpLine("/uz hp <jogador> lock", "Trava o HP atual"), false);
        source.sendFeedback(() -> helpLine("/uz hp <jogador> unlock", "Destrava o HP"), false);
        source.sendFeedback(() -> selfHint(), false);
        return 1;
    }

    private int experienceHelp(ServerCommandSource source) {
        source.sendFeedback(() -> header("XP · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz xp <jogador>", "Mostra os pontos totais e o nível"), false);
        source.sendFeedback(() -> helpLine("/uz xp <jogador> add <pontos>", "Adiciona pontos de XP"), false);
        source.sendFeedback(() -> helpLine("/uz xp <jogador> rm <pontos>", "Remove pontos de XP"), false);
        source.sendFeedback(() -> selfHint(), false);
        return 1;
    }

    private int killHelp(ServerCommandSource source) {
        source.sendFeedback(() -> header("Kill · administradores"), false);
        source.sendFeedback(() -> helpLine("/uz kill <jogador>", "Mata um jogador e envia uma mensagem"), false);
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

    private Text selfHint() {
        return Text.empty()
                .append(Text.literal("  Dica  ").formatted(Formatting.DARK_GRAY, Formatting.BOLD))
                .append(Text.literal("Use ").formatted(Formatting.GRAY))
                .append(Text.literal("self").formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal(" no lugar de <jogador> para operar sobre si mesmo.").formatted(Formatting.GRAY));
    }

    private Text playerName(ServerPlayerEntity player) {
        return playerName(player.getName().getString());
    }

    private Text playerName(String name) {
        return Text.literal(name).formatted(Formatting.AQUA, Formatting.BOLD);
    }

    private Text healthValue(float current, float maximum) {
        return Text.empty()
                .append(Text.literal(format(current)).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" / ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(format(maximum)).formatted(Formatting.RED));
    }

    private Text successPrefix() {
        return Text.literal("✓ ").formatted(Formatting.GREEN, Formatting.BOLD);
    }

    private Text warningPrefix() {
        return Text.literal("⚠ ").formatted(Formatting.YELLOW, Formatting.BOLD);
    }

    private Text errorPrefix() {
        return Text.literal("✕ ").formatted(Formatting.RED, Formatting.BOLD);
    }

    private Text error(String message) {
        return Text.empty()
                .append(errorPrefix())
                .append(Text.literal(message).formatted(Formatting.RED));
    }

    private String format(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
