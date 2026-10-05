package dev.chirana.umbrellaz.player;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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

public final class PlayerStatusCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStatusCommand.class);
    private final AuthorizationService authorizationService;
    private final HealthLockService healthLockService;
    private final FoodLockService foodLockService;
    private final OnlinePlayerResolver playerResolver;
    private final OnlinePlayerSuggestions playerSuggestions;
    private final AuditService auditService;

    public PlayerStatusCommand(AuthorizationService authorizationService) {
        this(authorizationService, new HealthLockService(), new FoodLockService(), new OnlinePlayerResolver(),
                new OnlinePlayerSuggestions(), null);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, FoodLockService foodLockService) {
        this(authorizationService, new HealthLockService(), foodLockService, new OnlinePlayerResolver(),
                new OnlinePlayerSuggestions(), null);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, HealthLockService healthLockService) {
        this(authorizationService, healthLockService, new FoodLockService(), new OnlinePlayerResolver(),
                new OnlinePlayerSuggestions(), null);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, HealthLockService healthLockService,
                               OnlinePlayerResolver playerResolver) {
        this(authorizationService, healthLockService, new FoodLockService(), playerResolver,
                new OnlinePlayerSuggestions(), null);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, HealthLockService healthLockService,
                               OnlinePlayerResolver playerResolver, OnlinePlayerSuggestions playerSuggestions) {
        this(authorizationService, healthLockService, new FoodLockService(), playerResolver, playerSuggestions, null);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, FoodLockService foodLockService,
                               OnlinePlayerResolver playerResolver,
                               OnlinePlayerSuggestions playerSuggestions) {
        this(authorizationService, new HealthLockService(), foodLockService, playerResolver, playerSuggestions, null);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, FoodLockService foodLockService,
                               OnlinePlayerResolver playerResolver,
                                OnlinePlayerSuggestions playerSuggestions, AuditService auditService) {
        this(authorizationService, new HealthLockService(), foodLockService, playerResolver, playerSuggestions,
                auditService);
    }

    public PlayerStatusCommand(AuthorizationService authorizationService, HealthLockService healthLockService,
                               FoodLockService foodLockService, OnlinePlayerResolver playerResolver,
                               OnlinePlayerSuggestions playerSuggestions, AuditService auditService) {
        this.authorizationService = authorizationService;
        this.healthLockService = healthLockService;
        this.foodLockService = foodLockService;
        this.playerResolver = playerResolver;
        this.playerSuggestions = playerSuggestions;
        this.auditService = auditService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz")
                .then(healthCommand("hp"))
                .then(experienceCommand("xp"))
                .then(foodCommand("food"))
                .then(foodCommand("hungry"))
                .then(killCommand()));
        dispatcher.register(Commands.literal("uz")
                .then(healthCommand("hp"))
                .then(experienceCommand("xp"))
                .then(foodCommand("food"))
                .then(foodCommand("hungry"))
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
                .suggests(playerSuggestions)
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
                        .suggests(playerSuggestions)
                        .executes(context -> killPlayer(
                                context.getSource(),
                                StringArgumentType.getString(context, "player"))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> foodCommand(String name) {
        return Commands.literal(name)
                .executes(context -> foodHelp(context.getSource()))
                .then(Commands.literal("help").executes(context -> foodHelp(context.getSource())))
                .then(selfFoodCommand())
                .then(foodPlayerCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> selfFoodCommand() {
        return Commands.literal("self")
                .executes(context -> showFood(context.getSource(), context.getSource().getPlayer(), "self"))
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> addFood(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                        "self"))))
                .then(Commands.literal("rm")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> removeFood(
                                        context.getSource(),
                                        context.getSource().getPlayer(),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                        "self"))))
                .then(Commands.literal("lock")
                        .executes(context -> lockFood(context.getSource(), context.getSource().getPlayer(), "self")))
                .then(Commands.literal("unlock")
                        .executes(context -> unlockFood(context.getSource(), context.getSource().getPlayer(), "self")));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> foodPlayerCommand() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(playerSuggestions)
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "player");
                    return showFood(context.getSource(), findPlayer(context.getSource(), name), name);
                })
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return addFood(context.getSource(), findPlayer(context.getSource(), name),
                                            IntegerArgumentType.getInteger(context, "amount"), name);
                                })))
                .then(Commands.literal("rm")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    return removeFood(context.getSource(), findPlayer(context.getSource(), name),
                                            IntegerArgumentType.getInteger(context, "amount"), name);
                                })))
                .then(Commands.literal("lock")
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            return lockFood(context.getSource(), findPlayer(context.getSource(), name), name);
                        }))
                .then(Commands.literal("unlock")
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            return unlockFood(context.getSource(), findPlayer(context.getSource(), name), name);
                        }));
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
                .suggests(playerSuggestions)
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

    private int showHealth(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailure(source, name, resolution.status());
        }
        return showHealth(source, resolution.player(), name);
    }

    private int addHealth(CommandSourceStack source, ServerPlayer player, double amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (player == null) {
            emitFailure(source, AuditActions.PLAYER_HEALTH_SET, "player_required");
            return unknownPlayer(source, name);
        }
        float current = player.getHealth();
        float next = Math.min(player.getMaxHealth(), current + (float) amount);
        player.setHealth(next);
        emitPlayerValue(source, AuditActions.PLAYER_HEALTH_SET, player, AuditPayload.health(next), "success");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP alterado para ").withStyle(ChatFormatting.GREEN))
                .append(healthValue(next, player.getMaxHealth())), true);
        return 1;
    }

    private int addHealth(CommandSourceStack source, OnlinePlayerResolution resolution, double amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_HEALTH_SET, name, resolution.status());
        }
        return addHealth(source, resolution.player(), amount, name);
    }

    private int lockHealth(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (player == null) {
            if (name.equals("self")) {
                emitFailure(source, AuditActions.PLAYER_HEALTH_SET, "player_required");
                source.sendFailure(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        float lockedHealth = Math.max(0.0f, Math.min(player.getMaxHealth(), player.getHealth()));
        healthLockService.lock(player.getUUID(), lockedHealth);
        emitPlayerValue(source, AuditActions.PLAYER_HEALTH_SET, player, AuditPayload.health(lockedHealth), "locked");
        source.sendSuccess(() -> Component.empty()
                .append(warningPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP travado em ").withStyle(ChatFormatting.YELLOW))
                .append(healthValue(lockedHealth, player.getMaxHealth())), true);
        return 1;
    }

    private int lockHealth(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_HEALTH_SET, name, resolution.status());
        }
        return lockHealth(source, resolution.player(), name);
    }

    private int unlockHealth(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (player == null) {
            if (name.equals("self")) {
                emitFailure(source, AuditActions.PLAYER_HEALTH_SET, "player_required");
                source.sendFailure(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        healthLockService.unlock(player.getUUID());
        emitPlayerValue(source, AuditActions.PLAYER_HEALTH_SET, player, null, "unlocked");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP destravado.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int unlockHealth(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_HEALTH_SET, name, resolution.status());
        }
        return unlockHealth(source, resolution.player(), name);
    }

    private int removeHealth(CommandSourceStack source, ServerPlayer player, double amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (player == null) {
            emitFailure(source, AuditActions.PLAYER_HEALTH_SET, "player_required");
            return unknownPlayer(source, name);
        }
        float current = player.getHealth();
        float next = Math.max(1.0f, current - (float) amount);
        player.setHealth(next);
        emitPlayerValue(source, AuditActions.PLAYER_HEALTH_SET, player, AuditPayload.health(next), "success");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  HP alterado para ").withStyle(ChatFormatting.GREEN))
                .append(healthValue(next, player.getMaxHealth())), true);
        return 1;
    }

    private int removeHealth(CommandSourceStack source, OnlinePlayerResolution resolution, double amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_HEALTH_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_HEALTH_SET, name, resolution.status());
        }
        return removeHealth(source, resolution.player(), amount, name);
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

    private int showExperience(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailure(source, name, resolution.status());
        }
        return showExperience(source, resolution.player(), name);
    }

    private int addExperience(CommandSourceStack source, ServerPlayer player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_EXPERIENCE_SET);
        }
        if (player == null) {
            emitFailure(source, AuditActions.PLAYER_EXPERIENCE_SET, "player_required");
            return unknownPlayer(source, name);
        }
        player.giveExperiencePoints(amount);
        emitPlayerValue(source, AuditActions.PLAYER_EXPERIENCE_SET, player,
                AuditPayload.experience(Math.max(0, player.totalExperience)), "success");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("+" + amount + " XP").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" para ").withStyle(ChatFormatting.GREEN))
                .append(playerName(player))
                .append(Component.literal("  ·  Total ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.totalExperience)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int addExperience(CommandSourceStack source, OnlinePlayerResolution resolution, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_EXPERIENCE_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_EXPERIENCE_SET, name, resolution.status());
        }
        return addExperience(source, resolution.player(), amount, name);
    }

    private int removeExperience(CommandSourceStack source, ServerPlayer player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_EXPERIENCE_SET);
        }
        if (player == null) {
            emitFailure(source, AuditActions.PLAYER_EXPERIENCE_SET, "player_required");
            return unknownPlayer(source, name);
        }
        player.giveExperiencePoints(-amount);
        emitPlayerValue(source, AuditActions.PLAYER_EXPERIENCE_SET, player,
                AuditPayload.experience(Math.max(0, player.totalExperience)), "success");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("−" + amount + " XP").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" de ").withStyle(ChatFormatting.GREEN))
                .append(playerName(player))
                .append(Component.literal("  ·  Total ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.totalExperience)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int removeExperience(CommandSourceStack source, OnlinePlayerResolution resolution, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_EXPERIENCE_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_EXPERIENCE_SET, name, resolution.status());
        }
        return removeExperience(source, resolution.player(), amount, name);
    }

    private int showFood(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (player == null) {
            return unknownPlayer(source, name);
        }
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("🍖 ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(playerName(player))
                .append(Component.literal("  FOME  ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(player.getFoodData().getFoodLevel()))
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" / 20").withStyle(ChatFormatting.GOLD)), false);
        return 1;
    }

    private int showFood(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailure(source, name, resolution.status());
        }
        return showFood(source, resolution.player(), name);
    }

    private int addFood(CommandSourceStack source, ServerPlayer player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (player == null) {
            emitFailure(source, AuditActions.PLAYER_FOOD_SET, "player_required");
            return unknownPlayer(source, name);
        }
        int next = FoodLevelArithmetic.add(player.getFoodData().getFoodLevel(), amount);
        player.getFoodData().setFoodLevel(next);
        emitPlayerValue(source, AuditActions.PLAYER_FOOD_SET, player, AuditPayload.food(next), "success");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("+" + amount + " fome").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" para ").withStyle(ChatFormatting.GREEN))
                .append(playerName(player))
                .append(Component.literal("  ·  Total ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(next) + " / 20")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int addFood(CommandSourceStack source, OnlinePlayerResolution resolution, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_FOOD_SET, name, resolution.status());
        }
        return addFood(source, resolution.player(), amount, name);
    }

    private int removeFood(CommandSourceStack source, ServerPlayer player, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (player == null) {
            emitFailure(source, AuditActions.PLAYER_FOOD_SET, "player_required");
            return unknownPlayer(source, name);
        }
        int next = FoodLevelArithmetic.remove(player.getFoodData().getFoodLevel(), amount);
        player.getFoodData().setFoodLevel(next);
        emitPlayerValue(source, AuditActions.PLAYER_FOOD_SET, player, AuditPayload.food(next), "success");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("−" + amount + " fome").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" de ").withStyle(ChatFormatting.GREEN))
                .append(playerName(player))
                .append(Component.literal("  ·  Total ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(next) + " / 20")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int removeFood(CommandSourceStack source, OnlinePlayerResolution resolution, int amount, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_FOOD_SET, name, resolution.status());
        }
        return removeFood(source, resolution.player(), amount, name);
    }

    private int lockFood(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (player == null) {
            if (name.equals("self")) {
                emitFailure(source, AuditActions.PLAYER_FOOD_SET, "player_required");
                source.sendFailure(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        int lockedFood = player.getFoodData().getFoodLevel();
        foodLockService.lock(player.getUUID(), lockedFood);
        emitPlayerValue(source, AuditActions.PLAYER_FOOD_SET, player, AuditPayload.food(lockedFood), "locked");
        source.sendSuccess(() -> Component.empty()
                .append(warningPrefix())
                .append(playerName(player))
                .append(Component.literal("  fome travada em ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(Integer.toString(lockedFood) + " / 20")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), true);
        return 1;
    }

    private int lockFood(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_FOOD_SET, name, resolution.status());
        }
        return lockFood(source, resolution.player(), name);
    }

    private int unlockFood(CommandSourceStack source, ServerPlayer player, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (player == null) {
            if (name.equals("self")) {
                emitFailure(source, AuditActions.PLAYER_FOOD_SET, "player_required");
                source.sendFailure(error("Este formato precisa ser executado por um jogador."));
                return 0;
            }
            return unknownPlayer(source, name);
        }
        foodLockService.unlock(player.getUUID());
        emitPlayerValue(source, AuditActions.PLAYER_FOOD_SET, player, null, "unlocked");
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(player))
                .append(Component.literal("  fome destravada.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int unlockFood(CommandSourceStack source, OnlinePlayerResolution resolution, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_FOOD_SET);
        }
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            return resolutionFailureWithAudit(source, AuditActions.PLAYER_FOOD_SET, name, resolution.status());
        }
        return unlockFood(source, resolution.player(), name);
    }

    private int killPlayer(CommandSourceStack source, String name) {
        if (!authorized(source)) {
            return deny(source, AuditActions.PLAYER_KILL);
        }
        OnlinePlayerResolution resolution = findPlayer(source, name);
        if (resolution.status() != OnlinePlayerResolution.Status.FOUND) {
            emit(source, AuditActions.PLAYER_KILL, Outcome.FAILURE,
                    AuditPayload.forAction(AuditActions.PLAYER_KILL));
            return resolutionFailure(source, name, resolution.status());
        }
        ServerPlayer player = resolution.player();
        healthLockService.clear(player.getUUID());
        player.sendSystemMessage(Component.literal("Admin matou você, seu boboca 😈")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        player.kill(player.level());
        emitKill(source, player);
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("☠ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(playerName(player))
                .append(Component.literal(" foi morto.").withStyle(ChatFormatting.RED)), true);
        return 1;
    }

    private OnlinePlayerResolution findPlayer(CommandSourceStack source, String name) {
        if (name.equalsIgnoreCase("self")) {
            ServerPlayer player = source.getPlayer();
            return player == null ? OnlinePlayerResolution.notFound() : OnlinePlayerResolution.found(player);
        }
        return playerResolver.resolve(source.getServer(), name);
    }

    private boolean authorized(CommandSourceStack source) {
        return CommandAuthorization.isAdministrator(source, authorizationService);
    }

    private int deny(CommandSourceStack source) {
        source.sendFailure(error("Você não tem autorização Umbrellaz para usar este comando."));
        return 0;
    }

    private int deny(CommandSourceStack source, String permission) {
        int result = deny(source);
        emitAuthorizationDenied(source, permission);
        return result;
    }

    private void emitAuthorizationDenied(CommandSourceStack source, String permission) {
        emit(source, AuditActions.AUTHORIZATION_COMMAND_DENIED, Outcome.DENIED,
                AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                        AuditPayload.permission(permission), AuditPayload.reasonCode("not_administrator")));
    }

    private void emitFailure(CommandSourceStack source, String action, String result) {
        emit(source, action, Outcome.FAILURE,
                AuditPayload.forAction(action, AuditPayload.result(result)));
    }

    private void emitPlayerValue(CommandSourceStack source, String action, ServerPlayer player,
                                 AuditPayload.Field value, String result) {
        try {
            AuditPayload payload = value == null
                    ? AuditPayload.forAction(action, AuditPayload.playerUuid(player.getUUID()),
                            AuditPayload.playerName(player.getName().getString()), AuditPayload.result(result))
                    : AuditPayload.forAction(action, AuditPayload.playerUuid(player.getUUID()),
                            AuditPayload.playerName(player.getName().getString()), value, AuditPayload.result(result));
            emit(source, action, Outcome.SUCCESS, payload);
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create player value audit payload", failure);
        }
    }

    private void emitKill(CommandSourceStack source, ServerPlayer player) {
        try {
            emit(source, AuditActions.PLAYER_KILL, Outcome.SUCCESS,
                    AuditPayload.forAction(AuditActions.PLAYER_KILL,
                            AuditPayload.victimEntityType("minecraft:player"), AuditPayload.victimUuid(player.getUUID()),
                            AuditPayload.victimName(player.getName().getString()), AuditPayload.itemType("minecraft:air")));
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create player kill audit payload", failure);
        }
    }

    private void emit(CommandSourceStack source, String action, Outcome outcome, AuditPayload payload) {
        if (auditService == null) {
            return;
        }
        AuditRecordContext context;
        if (source.getEntity() instanceof ServerPlayer player) {
            context = AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                    player.getName().getString()));
        } else {
            context = AuditRecordContext.forActor(new Actor(ActorType.CONSOLE, null, null));
        }
        try {
            auditService.record(new AuditRecordRequest(context, Source.COMMAND, action, outcome, null, null, 1,
                    payload, AuditDelivery.BEST_EFFORT)).exceptionally(failure -> {
                LOGGER.warn("Unable to record player status command audit event", failure);
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create player status command audit event", failure);
        }
    }

    private int unknownPlayer(CommandSourceStack source, String name) {
        source.sendFailure(Component.empty()
                .append(errorPrefix())
                .append(Component.literal("Jogador online não encontrado: ").withStyle(ChatFormatting.RED))
                .append(playerName(name)));
        return 0;
    }

    private int resolutionFailure(CommandSourceStack source, String name, OnlinePlayerResolution.Status status) {
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
        return 0;
    }

    private int resolutionFailureWithAudit(CommandSourceStack source, String action, String name,
                                           OnlinePlayerResolution.Status status) {
        emit(source, action, Outcome.FAILURE,
                AuditPayload.forAction(action, AuditPayload.result(resolutionCode(status))));
        return resolutionFailure(source, name, status);
    }

    private String resolutionCode(OnlinePlayerResolution.Status status) {
        return switch (status) {
            case AMBIGUOUS -> "ambiguous";
            case NOT_READY -> "not_ready";
            case NOT_FOUND -> "not_found";
            case FOUND -> "success";
        };
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

    private int foodHelp(CommandSourceStack source) {
        source.sendSuccess(() -> header("Fome · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz food <jogador>", "Mostra a fome atual de 0 a 20"), false);
        source.sendSuccess(() -> helpLine("/uz food <jogador> add <pontos>", "Adiciona fome até 20"), false);
        source.sendSuccess(() -> helpLine("/uz food <jogador> rm <pontos>", "Remove fome até 0"), false);
        source.sendSuccess(() -> helpLine("/uz food <jogador> lock", "Trava a fome atual"), false);
        source.sendSuccess(() -> helpLine("/uz food <jogador> unlock", "Destrava a fome"), false);
        source.sendSuccess(() -> helpLine("/uz hungry", "Atalho para /uz food"), false);
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
