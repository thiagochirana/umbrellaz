package dev.chirana.umbrellaz.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldTimeCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldTimeCommand.class);
    private static final int DAY = 1000;
    private static final int NOON = 6000;
    private static final int NIGHT = 13000;
    private static final int MIDNIGHT = 18000;

    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public WorldTimeCommand(AuthorizationService authorizationService) {
        this(authorizationService, null);
    }

    public WorldTimeCommand(AuthorizationService authorizationService, AuditService auditService) {
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz").then(timeCommand("time")));
        dispatcher.register(Commands.literal("uz").then(timeCommand("time")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> timeCommand(String name) {
        return Commands.literal(name)
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("day").executes(context -> setTime(context.getSource(), DAY, "day")))
                .then(Commands.literal("dia").executes(context -> setTime(context.getSource(), DAY, "dia")))
                .then(Commands.literal("noon").executes(context -> setTime(context.getSource(), NOON, "noon")))
                .then(Commands.literal("meio-dia").executes(context -> setTime(context.getSource(), NOON, "meio-dia")))
                .then(Commands.literal("night").executes(context -> setTime(context.getSource(), NIGHT, "night")))
                .then(Commands.literal("noite").executes(context -> setTime(context.getSource(), NIGHT, "noite")))
                .then(Commands.literal("midnight").executes(context -> setTime(context.getSource(), MIDNIGHT, "midnight")))
                .then(Commands.literal("meia-noite").executes(context -> setTime(context.getSource(), MIDNIGHT, "meia-noite")))
                .then(Commands.argument("time", IntegerArgumentType.integer(0))
                        .executes(context -> setTime(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "time"),
                                "valor personalizado")));
    }

    private int setTime(CommandSourceStack source, int time, String label) {
        AuditRecordContext auditContext = auditContext(source);
        if (!authorized(source)) {
            source.sendFailure(error("Você não tem autorização Umbrellaz para alterar o tempo."));
            emit(auditContext, Outcome.DENIED, AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                    AuditPayload.permission("world.time.set"), AuditPayload.reasonCode("not_administrator")),
                    AuditActions.AUTHORIZATION_COMMAND_DENIED);
            return 0;
        }
        for (ServerLevel world : source.getServer().getAllLevels()) {
            world.dimensionTypeRegistration().value().defaultClock()
                    .ifPresent(clock -> {
                        source.getServer().clockManager().setTotalTicks(clock, time);
                        emit(auditContext, Outcome.SUCCESS, AuditPayload.forAction(AuditActions.WORLD_TIME_SET,
                                AuditPayload.world(world.dimension().identifier().toString()),
                                AuditPayload.timeOfDay(time), AuditPayload.result("success")),
                                AuditActions.WORLD_TIME_SET);
                    });
        }
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("Tempo definido: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(label).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(Integer.toString(time) + " ticks").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" em todos os mundos.").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private boolean authorized(CommandSourceStack source) {
        return CommandAuthorization.isAdministrator(source, authorizationService);
    }

    private AuditRecordContext auditContext(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                    player.getName().getString()));
        }
        return AuditRecordContext.forActor(new Actor(ActorType.CONSOLE, null, null));
    }

    private void emit(AuditRecordContext context, Outcome outcome, AuditPayload payload, String action) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.record(new AuditRecordRequest(context, Source.COMMAND, action, outcome, null, null, 1,
                    payload, AuditDelivery.BEST_EFFORT)).exceptionally(failure -> {
                LOGGER.warn("Unable to record world time command audit event", failure);
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create world time command audit event", failure);
        }
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("Tempo · administradores"), false);
        source.sendSuccess(() -> helpLine("/uz time day", "Define o período diurno Vanilla (1000 ticks)"), false);
        source.sendSuccess(() -> helpLine("/uz time dia", "Atalho em português para day"), false);
        source.sendSuccess(() -> helpLine("/uz time noon", "Define o meio-dia (6000 ticks)"), false);
        source.sendSuccess(() -> helpLine("/uz time meio-dia", "Atalho em português para noon"), false);
        source.sendSuccess(() -> helpLine("/uz time night", "Define a noite Vanilla (13000 ticks)"), false);
        source.sendSuccess(() -> helpLine("/uz time noite", "Atalho em português para night"), false);
        source.sendSuccess(() -> helpLine("/uz time midnight", "Define meia-noite (18000 ticks)"), false);
        source.sendSuccess(() -> helpLine("/uz time meia-noite", "Atalho em português para midnight"), false);
        source.sendSuccess(() -> helpLine("/uz time <número>", "Define um valor inteiro de ticks"), false);
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
