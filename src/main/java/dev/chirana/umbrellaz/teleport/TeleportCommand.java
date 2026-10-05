package dev.chirana.umbrellaz.teleport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import dev.chirana.umbrellaz.audit.Actor;
import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditPayload;
import dev.chirana.umbrellaz.audit.AuditRecordContext;
import dev.chirana.umbrellaz.audit.AuditRecordRequest;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import dev.chirana.umbrellaz.player.OnlinePlayerResolver;
import dev.chirana.umbrellaz.player.OnlinePlayerSuggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TeleportCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeleportCommand.class);
    private final AuthorizationService authorizationService;
    private final OnlinePlayerResolver playerResolver;
    private final OnlinePlayerSuggestions playerSuggestions;
    private final AuditService auditService;

    public TeleportCommand(AuthorizationService authorizationService) {
        this(authorizationService, new OnlinePlayerResolver(), new OnlinePlayerSuggestions(), null);
    }

    public TeleportCommand(AuthorizationService authorizationService, OnlinePlayerResolver playerResolver) {
        this(authorizationService, playerResolver, new OnlinePlayerSuggestions(), null);
    }

    public TeleportCommand(AuthorizationService authorizationService, OnlinePlayerResolver playerResolver,
                           OnlinePlayerSuggestions playerSuggestions) {
        this(authorizationService, playerResolver, playerSuggestions, null);
    }

    public TeleportCommand(AuthorizationService authorizationService, OnlinePlayerResolver playerResolver,
                           OnlinePlayerSuggestions playerSuggestions, AuditService auditService) {
        this.authorizationService = authorizationService;
        this.playerResolver = playerResolver;
        this.playerSuggestions = playerSuggestions;
        this.auditService = auditService;
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
        AuditRecordContext auditContext = auditContext(source);
        if (!authorized(source)) {
            deny(source, auditContext, "teleport.execute");
            return 0;
        }
        ServerPlayer executor = source.getPlayer();
        if (executor == null) {
            emit(auditContext, Outcome.FAILURE, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                    AuditPayload.result("player_required")));
            source.sendFailure(error("Este formato precisa ser executado por um jogador."));
            return 0;
        }
        var destinationResolution = findPlayer(source.getServer(), destinationName);
        if (destinationResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            emit(auditContext, Outcome.FAILURE, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                    AuditPayload.result(resolutionCode(destinationResolution.status()))));
            reportResolution(source, destinationName, destinationResolution.status());
            return 0;
        }
        ServerPlayer destination = destinationResolution.player();
        String sourceWorld = worldId(executor.level());
        int sourceX = executor.blockPosition().getX();
        int sourceY = executor.blockPosition().getY();
        int sourceZ = executor.blockPosition().getZ();
        String targetWorld = worldId(destination.level());
        int targetX = destination.blockPosition().getX();
        int targetY = destination.blockPosition().getY();
        int targetZ = destination.blockPosition().getZ();
        if (!move(executor, destination)) {
            emitTeleportFailure(auditContext, sourceWorld, sourceX, sourceY, sourceZ,
                    targetWorld, targetX, targetY, targetZ);
            reportTeleportFailure(source);
            return 0;
        }
        emitTeleportSuccess(auditContext, sourceWorld, sourceX, sourceY, sourceZ, executor);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(Component.literal("Você foi teleportado até ").withStyle(ChatFormatting.GREEN))
                .append(playerName(destinationName))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int teleportPlayerToPlayer(CommandSourceStack source, String playerName, String targetName) {
        AuditRecordContext auditContext = auditContext(source);
        if (!authorized(source)) {
            deny(source, auditContext, "teleport.execute");
            return 0;
        }
        var playerResolution = findPlayer(source.getServer(), playerName);
        if (playerResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            emit(auditContext, Outcome.FAILURE, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                    AuditPayload.result(resolutionCode(playerResolution.status()))));
            reportResolution(source, playerName, playerResolution.status());
            return 0;
        }
        var targetResolution = findPlayer(source.getServer(), targetName);
        if (targetResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            emit(auditContext, Outcome.FAILURE, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                    AuditPayload.result(resolutionCode(targetResolution.status()))));
            reportResolution(source, targetName, targetResolution.status());
            return 0;
        }
        ServerPlayer player = playerResolution.player();
        ServerPlayer target = targetResolution.player();
        String sourceWorld = worldId(player.level());
        int sourceX = player.blockPosition().getX();
        int sourceY = player.blockPosition().getY();
        int sourceZ = player.blockPosition().getZ();
        String targetWorld = worldId(target.level());
        int targetX = target.blockPosition().getX();
        int targetY = target.blockPosition().getY();
        int targetZ = target.blockPosition().getZ();
        if (!move(player, target)) {
            emitTeleportFailure(auditContext, sourceWorld, sourceX, sourceY, sourceZ,
                    targetWorld, targetX, targetY, targetZ);
            reportTeleportFailure(source);
            return 0;
        }
        emitTeleportSuccess(auditContext, sourceWorld, sourceX, sourceY, sourceZ, player);
        source.sendSuccess(() -> Component.empty()
                .append(successPrefix())
                .append(playerName(playerName))
                .append(Component.literal(" foi teleportado até ").withStyle(ChatFormatting.GREEN))
                .append(playerName(targetName))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private int teleportPlayerToCoordinates(CommandSourceStack source, String playerName, double x, double y, double z) {
        AuditRecordContext auditContext = auditContext(source);
        if (!authorized(source)) {
            deny(source, auditContext, "teleport.execute");
            return 0;
        }
        var playerResolution = findPlayer(source.getServer(), playerName);
        if (playerResolution.status() != dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status.FOUND) {
            emit(auditContext, Outcome.FAILURE, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                    AuditPayload.result(resolutionCode(playerResolution.status()))));
            reportResolution(source, playerName, playerResolution.status());
            return 0;
        }
        ServerPlayer player = playerResolution.player();
        ServerLevel world = source.getLevel();
        if (world == null) {
            world = source.getServer().overworld();
        }
        String sourceWorld = worldId(player.level());
        int sourceX = player.blockPosition().getX();
        int sourceY = player.blockPosition().getY();
        int sourceZ = player.blockPosition().getZ();
        int targetX = coordinate(x);
        int targetY = coordinate(y);
        int targetZ = coordinate(z);
        String targetWorld = worldId(world);
        boolean teleported = player.teleportTo(world, x, y, z, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
        if (!teleported) {
            emitTeleportFailure(auditContext, sourceWorld, sourceX, sourceY, sourceZ,
                    targetWorld, targetX, targetY, targetZ);
            reportTeleportFailure(source);
            return 0;
        }
        emitTeleportSuccess(auditContext, sourceWorld, sourceX, sourceY, sourceZ, player);
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

    private boolean move(ServerPlayer player, ServerPlayer target) {
        return player.teleportTo(target.level(), target.getX(), target.getY(), target.getZ(),
                java.util.Set.of(), target.getYRot(), target.getXRot(), true);
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

    private void reportTeleportFailure(CommandSourceStack source) {
        source.sendFailure(error("Não foi possível realizar o teleporte."));
    }

    private void deny(CommandSourceStack source, AuditRecordContext context, String permission) {
        deny(source);
        emit(context, Outcome.DENIED, AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                AuditPayload.permission(permission), AuditPayload.reasonCode("not_administrator")),
                AuditActions.AUTHORIZATION_COMMAND_DENIED);
    }

    private AuditRecordContext auditContext(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return AuditRecordContext.forActor(new Actor(ActorType.PLAYER, player.getUUID(),
                    player.getName().getString()));
        }
        return AuditRecordContext.forActor(new Actor(ActorType.CONSOLE, null, null));
    }

    private void emitTeleportSuccess(AuditRecordContext context, String sourceWorld,
                                     int sourceX, int sourceY, int sourceZ, ServerPlayer player) {
        ServerLevel finalWorld = player.level();
        var finalPosition = player.blockPosition();
        emit(context, Outcome.SUCCESS, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                AuditPayload.sourceWorld(sourceWorld), AuditPayload.sourcePosition(sourceX, sourceY, sourceZ),
                AuditPayload.targetWorld(worldId(finalWorld)),
                AuditPayload.targetPosition(finalPosition.getX(), finalPosition.getY(), finalPosition.getZ()),
                AuditPayload.result("success")), AuditActions.TELEPORT_EXECUTED);
    }

    private void emitTeleportFailure(AuditRecordContext context, String sourceWorld,
                                     int sourceX, int sourceY, int sourceZ, String targetWorld,
                                     int targetX, int targetY, int targetZ) {
        emit(context, Outcome.FAILURE, AuditPayload.forAction(AuditActions.TELEPORT_EXECUTED,
                AuditPayload.sourceWorld(sourceWorld), AuditPayload.sourcePosition(sourceX, sourceY, sourceZ),
                AuditPayload.targetWorld(targetWorld), AuditPayload.targetPosition(targetX, targetY, targetZ),
                AuditPayload.result("teleport_failed")), AuditActions.TELEPORT_EXECUTED);
    }

    private int coordinate(double value) {
        double floored = Math.floor(value);
        if (floored <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (floored >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) floored;
    }

    private void emit(AuditRecordContext context, Outcome outcome, AuditPayload payload) {
        emit(context, outcome, payload, AuditActions.TELEPORT_EXECUTED);
    }

    private void emit(AuditRecordContext context, Outcome outcome, AuditPayload payload, String action) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.record(new AuditRecordRequest(context, Source.COMMAND, action, outcome, null, null, 1,
                    payload, dev.chirana.umbrellaz.audit.AuditDelivery.BEST_EFFORT)).exceptionally(failure -> {
                LOGGER.warn("Unable to record teleport command audit event", failure);
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create teleport command audit event", failure);
        }
    }

    private String worldId(ServerLevel world) {
        return world.dimension().identifier().toString();
    }

    private String resolutionCode(dev.chirana.umbrellaz.player.OnlinePlayerResolution.Status status) {
        return switch (status) {
            case AMBIGUOUS -> "ambiguous";
            case NOT_READY -> "not_ready";
            case NOT_FOUND -> "not_found";
            case FOUND -> "success";
        };
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
