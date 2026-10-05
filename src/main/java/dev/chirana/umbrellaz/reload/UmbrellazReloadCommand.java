package dev.chirana.umbrellaz.reload;

import com.mojang.brigadier.CommandDispatcher;
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

public final class UmbrellazReloadCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(UmbrellazReloadCommand.class);
    private final AuthorizationService authorizationService;
    private final UmbrellazReloadService reloadService;
    private final AuditService auditService;

    public UmbrellazReloadCommand(
            AuthorizationService authorizationService,
            UmbrellazReloadService reloadService) {
        this(authorizationService, reloadService, null);
    }

    public UmbrellazReloadCommand(
            AuthorizationService authorizationService,
            UmbrellazReloadService reloadService,
            AuditService auditService) {
        this.authorizationService = authorizationService;
        this.reloadService = reloadService;
        this.auditService = auditService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("umbrellaz").then(reloadCommand()));
        dispatcher.register(Commands.literal("uz").then(reloadCommand()));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> reloadCommand() {
        return Commands.literal("reload")
                .executes(context -> reload(context.getSource()));
    }

    private int reload(CommandSourceStack source) {
        AuditRecordContext auditContext = auditContext(source);
        if (!CommandAuthorization.isAdministrator(source, authorizationService)) {
            source.sendFailure(error("Você não tem autorização Umbrellaz para recarregar os caches."));
            emit(auditContext, Outcome.DENIED, AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                    AuditPayload.permission("runtime.reload"), AuditPayload.reasonCode("not_administrator")),
                    AuditActions.AUTHORIZATION_COMMAND_DENIED);
            return 0;
        }

        reloadService.reload()
                .thenRun(() -> source.getServer().execute(() -> {
                    emit(auditContext, Outcome.SUCCESS, AuditPayload.forAction(AuditActions.RUNTIME_RELOAD,
                            AuditPayload.component("caches"), AuditPayload.result("success")),
                            AuditActions.RUNTIME_RELOAD);
                    source.sendSuccess(() -> success("Caches Umbrellaz recarregados com sucesso."), false);
                }))
                .exceptionally(throwable -> {
                    emit(auditContext, Outcome.FAILURE, AuditPayload.forAction(AuditActions.RUNTIME_RELOAD,
                            AuditPayload.component("caches"), AuditPayload.result("failed")),
                            AuditActions.RUNTIME_RELOAD);
                    LOGGER.warn("Umbrellaz reload command failed", throwable);
                    source.getServer().execute(() -> source.sendFailure(error(
                            "Não foi possível recarregar os caches do Umbrellaz."
                                    + " Nenhuma autorização nova foi liberada.")));
                    return null;
                });
        return 1;
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
                LOGGER.warn("Unable to record reload command audit event", failure);
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to create reload command audit event", failure);
        }
    }

    private Component success(String message) {
        return Component.empty()
                .append(Component.literal("✓ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(message).withStyle(ChatFormatting.GREEN));
    }

    private Component error(String message) {
        return Component.empty()
                .append(Component.literal("✕ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
