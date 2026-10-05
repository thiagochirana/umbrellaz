package dev.chirana.umbrellaz.world;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldSkyCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldSkyCommand.class);
    private final AuthorizationService authorizationService;
    private final WorldSkyService skyService;
    private final AuditService auditService;

    public WorldSkyCommand(AuthorizationService authorizationService, WorldSkyService skyService) {
        this(authorizationService, skyService, null);
    }

    public WorldSkyCommand(AuthorizationService authorizationService, AuditService auditService,
                           WorldSkyService skyService) {
        this(authorizationService, skyService, auditService);
    }

    public WorldSkyCommand(AuthorizationService authorizationService, WorldSkyService skyService,
                           AuditService auditService) {
        this.authorizationService = authorizationService;
        this.skyService = skyService;
        this.auditService = auditService;
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
        AuditRecordContext auditContext = auditContext(source);
        if (!CommandAuthorization.isAdministrator(source, authorizationService)) {
            source.sendFailure(error("Você não tem autorização Umbrellaz para alterar o clima."));
            emit(auditContext, Outcome.DENIED, AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                    AuditPayload.permission("world.sky.set"), AuditPayload.reasonCode("not_administrator")),
                    AuditActions.AUTHORIZATION_COMMAND_DENIED);
            return 0;
        }
        skyService.apply(source.getServer(), mode);
        source.getServer().getAllLevels().forEach(world -> emit(auditContext, Outcome.SUCCESS,
                AuditPayload.forAction(AuditActions.WORLD_SKY_SET,
                        AuditPayload.world(world.dimension().identifier().toString()),
                        AuditPayload.skyVisible(mode == WorldSkyMode.CLEAN), AuditPayload.result("success")),
                AuditActions.WORLD_SKY_SET));
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
                LOGGER.warn("Unable to record world sky command audit event", failure);
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create world sky command audit event", failure);
        }
    }
}
