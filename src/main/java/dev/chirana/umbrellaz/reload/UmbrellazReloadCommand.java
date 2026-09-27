package dev.chirana.umbrellaz.reload;

import com.mojang.brigadier.CommandDispatcher;
import dev.chirana.umbrellaz.authorization.AuthorizationService;
import dev.chirana.umbrellaz.authorization.CommandAuthorization;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class UmbrellazReloadCommand {
    private final AuthorizationService authorizationService;
    private final UmbrellazReloadService reloadService;

    public UmbrellazReloadCommand(
            AuthorizationService authorizationService,
            UmbrellazReloadService reloadService) {
        this.authorizationService = authorizationService;
        this.reloadService = reloadService;
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
        if (!CommandAuthorization.isAdministrator(source, authorizationService)) {
            source.sendFailure(error("Você não tem autorização Umbrellaz para recarregar os caches."));
            return 0;
        }

        reloadService.reload()
                .thenRun(() -> source.getServer().execute(() -> source.sendSuccess(
                        () -> success("Caches Umbrellaz recarregados com sucesso."), false)))
                .exceptionally(throwable -> {
                    source.getServer().execute(() -> source.sendFailure(error(
                            failureMessage(throwable) + " Nenhuma autorização nova foi liberada.")));
                    return null;
                });
        return 1;
    }

    private String failureMessage(Throwable throwable) {
        Throwable failure = throwable;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure.getMessage() == null
                ? "Não foi possível recarregar os caches Umbrellaz."
                : failure.getMessage() + ".";
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
