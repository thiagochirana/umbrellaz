package dev.chirana.umbrellaz.player;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerStatusCommand {
    private final AuthorizationService authorizationService;
    private final HealthLockService healthLockService;

    public PlayerStatusCommand(AuthorizationService authorizationService, HealthLockService healthLockService) {
        this.authorizationService = authorizationService;
        this.healthLockService = healthLockService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz")
                .then(healthCommand("hp"))
                .then(experienceCommand("xp"))
                .then(killCommand()));
        dispatcher.register(Commands.literal("uz")
                .then(healthCommand("hp"))
                .then(experienceCommand("xp"))
                .then(killCommand()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> healthCommand(String name) {
        return Commands.literal(name)
                .executes(context -> healthHelp(context.getSource()))
                .then(Commands.literal("help").executes(context -> healthHelp(context.getSource())))
                .then(selfHealthCommand())
                .then(healthPlayerCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> selfHealthCommand() {
        return Commands.literal("self")
                .executes(context -> showHealth(context.getSource(), context.getSource().getPlayer(), "self"))
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> addHealth(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        DoubleArgumentType.getDouble(context, "amount"),
                                        "self"))))
                .then(Commands.literal("rm")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> removeHealth(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        DoubleArgumentType.getDouble(context, "amount"),
                                        "self"))))
                .then(Commands.literal("lock")
                        .executes(context -> lockHealth(context.getSource(), context.getSource().getPlayer(), "self")))
                .then(Commands.literal("unlock")
                        .executes(context -> unlockHealth(context.getSource(), context.getSource().getPlayer(), "self")));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> healthPlayerCommand() {
        return Commands.argument("player", StringArgumentType.word())
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "player");
                    return showHealth(context.getSource(), findPlayer(context.getSource(), name), name);
                })
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return addHealth(context.getSource(), findPlayer(context.getSource(), name),
                                            DoubleArgumentType.getDouble(context, "amount"), name);
                                })))
                .then(Commands.literal("rm")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return removeHealth(context.getSource(), findPlayer(context.getSource(), name),
                                            DoubleArgumentType.getDouble(context, "amount"), name);
                                })))
                .then(Commands.literal("lock")
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            return lockHealth(context.getSource(), findPlayer(context.getSource(), name), name);
                        }))
                .then(Commands.literal("unlock")
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            return unlockHealth(context.getSource(), findPlayer(context.getSource(), name), name);
                        }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> experienceCommand(String name) {
        return Commands.literal(name)
                .executes(context -> experienceHelp(context.getSource()))
                .then(Commands.literal("help").executes(context -> experienceHelp(context.getSource())))
                .then(selfExperienceCommand())
                .then(experiencePlayerCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> killCommand() {
        return Commands.literal("kill")
                .executes(context -> killHelp(context.getSource()))
                .then(Commands.literal("help").executes(context -> killHelp(context.getSource())))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> killPlayer(
                                context.getSource(),
                                StringArgumentType.getString(context, "player"))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> selfExperienceCommand() {
        return Commands.literal("self")
                .executes(context -> showExperience(context.getSource(), context.getSource().getPlayer(), "self"))
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> addExperience(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                        "self"))))
                .then(Commands.literal("rm")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> removeExperience(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                        "self"))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> experiencePlayerCommand() {
        return Commands.argument("player", StringArgumentType.word())
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "player");
                    return showExperience(context.getSource(), findPlayer(context.getSource(), name), name);
                })
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return addExperience(context.getSource(), findPlayer(context.getSource(), name),
                                            IntegerArgumentType.getInteger(context, "amount"), name);
                                })))
                .then(Commands.literal("rm")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return removeExperience(context.getSource(), findPlayer(context.getSource(), name),
                                            IntegerArgumentType.getInteger(context, "amount"), name);
                                })));
    }

    private int showHealth(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("♥ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(playerName(player))
                .append(Component.literal("  HP  ").withStyle(ChatFormatting.GRAY))
                .append(healthValue(player.getHealth(), player.getMaxHealth())), false);
        return 1;
    }

    private int addHealth(CommandSourceStack source, ServerPlayer player, double amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        float current = player.getHealth();
        float next = Math.min(player.getMaxHealth(), current + (float) amount);
        player.setHealth(next);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP alterado para ").withStyle(ChatFormatting.GREEN))
                .append(healthValue(next, player.getMaxHealth())), true);
        return 1;
    }

    private int lockHealth(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            if (name.equals("self")) {
                source.sendFailure(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        float lockedHealth = Math.max(1.0f, Math.min(player.getMaxHealth(), player.getHealth()));
        healthLockService.lock(player.getUUID(), lockedHealth);
        player.setHealth(lockedHealth);
        source.sendSuccess(() -> Component.empty()
                .append(warningPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP travado em ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(format(lockedHealth)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int unlockHealth(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            if (name.equals("self")) {
                source.sendFailure(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        healthLockService.unlock(player.getUUID());
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP destravado.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int removeHealth(CommandSourceStack source, ServerPlayer player, double amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        float current = player.getHealth();
        float next = Math.max(1.0f, current - (float) amount);
        player.setHealth(next);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP alterado para ").withStyle(ChatFormatting.GREEN))
                .append(healthValue(next, player.getMaxHealth())), true);
        return 1;
    }

    private int showExperience(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("✦ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(playerName(player))
                .append(Component.literal("  XP  ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.totalExperience)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" pontos  ·  nível ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.experienceLevel)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), false);
        return 1;
    }

    private int addExperience(CommandSourceStack source, ServerPlayer player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        player.giveExperiencePoints(amount);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("+" + amount + " XP").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" para ").withStyle(ChatFormatting.GREEN))
                .append(playerName(player))
                .append(Component.literal("  ·  Total ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.totalExperience)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int removeExperience(CommandSourceStack source, ServerPlayer player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        player.giveExperiencePoints(-amount);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("−" + amount + " XP").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" de ").withStyle(ChatFormatting.GREEN))
                .append(playerName(player))
                .append(Component.literal("  ·  Total ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.totalExperience)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int killPlayer(CommandSourceStack source, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        ServerPlayer player = findPlayer(source, name);
        if (player == null) {
            return unknownPlayer(source, name);
        }
        healthLockService.unlock(player.getUUID());
        player.sendSystemMessage(Component.literal("Admin matou você, seu boboca 😈")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        player.kill(player.level());
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("☠ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(playerName(player))
                .append(Component.literal(" foi morto.").withStyle(ChatFormatting.RED)), true);
        return 1;
    }

    private ServerPlayer findPlayer(CommandSourceStack source, String name) {
        if (name.equalsIgnoreCase("self")) {
            return source.getPlayer();
        }
        return source.getServer().getPlayerList().getPlayerByName(name);
    }

    private boolean authorized(CommandSourceStack source) {
        return source.getEntity() == null
                || source.getEntity() instanceof ServerPlayer player
                && authorizationService.isAdministrator(player.getUUID());
    }

    private int deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para usar este comando."));
        return 0;
    }

    private int unknownPlayer(CommandSourceStack source, String name) {
        source.sendFailure(Component.empty()
                .append(errorPrefix())
                .append(Component.literal("Jogador online não encontrado: ").withStyle(ChatFormatting.RED))
                .append(playerName(name)));
        return 0;
    }

    private int healthHelp(CommandSourceStack source) {
        source.sendSuccess(() -> header("HP · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz hp <jogador>", "Mostra o HP atual e máximo"), false);
        source.sendSuccess(() -> helpLine("/uz hp <jogador> add <pontos>", "Adiciona HP até o máximo"), false);
        source.sendSuccess(() -> helpLine("/uz hp <jogador> rm <pontos>", "Remove HP sem chegar abaixo de 1"), false);
        source.sendSuccess(() -> helpLine("/uz hp <jogador> lock", "Trava o HP atual"), false);
        source.sendSuccess(() -> helpLine("/uz hp <jogador> unlock", "Destrava o HP"), false);
        source.sendSuccess(() -> selfHint(), false);
        return 1;
    }

    private int experienceHelp(CommandSourceStack source) {
        source.sendSuccess(() -> header("XP · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz xp <jogador>", "Mostra os pontos totais e o nível"), false);
        source.sendSuccess(() -> helpLine("/uz xp <jogador> add <pontos>", "Adiciona pontos de XP"), false);
        source.sendSuccess(() -> helpLine("/uz xp <jogador> rm <pontos>", "Remove pontos de XP"), false);
        source.sendSuccess(() -> selfHint(), false);
        return 1;
    }

    private int killHelp(CommandSourceStack source) {
        source.sendSuccess(() -> header("Kill · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz kill <jogador>", "Mata um jogador e envia uma mensagem"), false);
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

    private Component selfHint() {
        return Component.empty()
                .append(Component.literal("  Dica  ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                .append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("self").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" no lugar de <jogador> para operar sobre si mesmo.").withStyle(ChatFormatting.GRAY));
    }

    private Component playerName(ServerPlayer player) {
        return playerName(player.getName().getString());
    }

    private Component playerName(String name) {
        return Component.literal(name).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private Component healthValue(float current, float maximum) {
        return Component.empty()
                .append(Component.literal(format(current)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(format(maximum)).withStyle(ChatFormatting.RED));
    }

    private Component successPrefix() {
        return Component.literal("✓ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
    }

    private Component warningPrefix() {
        return Component.literal("⚠ ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
    }

    private Component errorPrefix() {
        return Component.literal("✕ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    private Component error(String message) {
        return Component.empty()
                .append(errorPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private String format(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
