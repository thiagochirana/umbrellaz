package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.gui.LockPromptCompletion;
import dev.chirana.umbrellaz.gui.LockPromptMode;
import dev.chirana.umbrellaz.gui.LockPromptScreen;
import dev.chirana.umbrellaz.protocol.LockPromptCancelPayload;
import dev.chirana.umbrellaz.protocol.LockPromptOpenPayload;
import dev.chirana.umbrellaz.protocol.LockPromptResultCode;
import dev.chirana.umbrellaz.protocol.LockPromptResultPayload;
import dev.chirana.umbrellaz.protocol.LockPromptSubmitPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;

import java.util.Objects;
import java.util.UUID;

final class LockPromptController {
    private static final int REQUEST_TIMEOUT_TICKS = 200;
    private static final String REQUEST_TIMEOUT_MESSAGE = "O pedido do cadeado expirou.";

    private Connection connection;
    private UUID activeContextToken;
    private UUID pendingContextToken;
    private LockPromptCompletion pendingCompletion;
    private LockPromptScreen activeScreen;
    private Screen previousScreen;
    private boolean cancelSent;
    private int pendingRequestTicks;

    void receiveOpen(Minecraft client, LockPromptOpenPayload payload, Connection packetConnection) {
        if (!isCurrentConnection(client, packetConnection)) {
            return;
        }

        if (activeScreen != null) {
            dismiss(client, activeScreen, true);
        }

        LockPromptMode mode = switch (payload.promptKind()) {
            case CREATE -> LockPromptMode.CREATE_PASSWORD;
            case CONFIRM -> LockPromptMode.CONFIRM_PASSWORD;
        };
        Screen promptPrevious = client.gui.screen();
        LockPromptScreen prompt = new LockPromptScreen(
                mode,
                (ignoredMode, password, completion) -> submit(
                        client, password, completion),
                () -> dismissCurrent(client, true),
                () -> restoreCurrent(client));

        connection = packetConnection;
        activeContextToken = payload.contextToken();
        pendingContextToken = null;
        pendingCompletion = null;
        pendingRequestTicks = 0;
        activeScreen = prompt;
        previousScreen = promptPrevious;
        cancelSent = false;
        client.gui.setScreen(prompt);
    }

    void receiveResult(Minecraft client, LockPromptResultPayload payload, Connection packetConnection) {
        if (!isCurrentConnection(client, packetConnection)
                || connection != packetConnection
                || pendingContextToken == null
                || !pendingContextToken.equals(payload.contextToken())
                || pendingCompletion == null
                || activeScreen == null
                || client.gui.screen() != activeScreen) {
            return;
        }

        LockPromptCompletion completion = pendingCompletion;
        pendingContextToken = null;
        pendingCompletion = null;
        pendingRequestTicks = 0;

        switch (payload.resultCode()) {
            case ERROR, COOLDOWN -> {
                UUID retryToken = payload.retryContextToken();
                if (retryToken == null) {
                    invalidatePrompt(client, activeScreen, completion,
                            "O pedido do cadeado foi invalidado.");
                    return;
                }
                activeContextToken = retryToken;
                if (payload.resultCode() == LockPromptResultCode.ERROR) {
                    completion.error(payload.message());
                } else {
                    completion.cooldown(payload.cooldownSeconds());
                }
            }
            case SUCCESS -> {
                clearPromptTokens();
                completion.close();
            }
            case INVALIDATED -> {
                clearPromptTokens();
                completion.close();
            }
        }
    }

    void onDisconnect(Minecraft client, Connection disconnectedConnection) {
        if (connection == null || connection != disconnectedConnection) {
            return;
        }

        clearPromptTokens();
        connection = null;
        activeScreen = null;
        previousScreen = null;
        cancelSent = false;
        if (client.gui.screen() instanceof LockPromptScreen) {
            client.gui.setScreen(null);
        }
    }

    void tick(Minecraft client) {
        if (activeScreen == null) {
            return;
        }
        if (client.gui.screen() != activeScreen) {
            dismiss(client, activeScreen, true);
            return;
        }
        if (pendingContextToken != null && ++pendingRequestTicks >= REQUEST_TIMEOUT_TICKS) {
            invalidatePendingPrompt(client, REQUEST_TIMEOUT_MESSAGE);
        }
    }

    private void submit(
            Minecraft client,
            String password,
            LockPromptCompletion completion
    ) {
        if (activeScreen == null
                || client.gui.screen() != activeScreen
                || activeContextToken == null
                || pendingContextToken != null) {
            completion.error("O pedido do cadeado expirou.");
            return;
        }

        pendingContextToken = activeContextToken;
        pendingCompletion = Objects.requireNonNull(completion, "completion");
        pendingRequestTicks = 0;
        try {
            if (!ClientPlayNetworking.canSend(LockPromptSubmitPayload.TYPE)) {
                throw new IllegalStateException("Lock prompt submit is not sendable");
            }
            ClientPlayNetworking.send(new LockPromptSubmitPayload(pendingContextToken, password));
        } catch (RuntimeException exception) {
            pendingContextToken = null;
            pendingCompletion = null;
            pendingRequestTicks = 0;
            completion.error("Não foi possível enviar o pedido.");
        }
    }

    private void invalidatePendingPrompt(Minecraft client, String message) {
        LockPromptScreen prompt = activeScreen;
        if (prompt == null || pendingContextToken == null || pendingCompletion == null
                || client.gui.screen() != prompt) {
            return;
        }

        invalidatePrompt(client, prompt, pendingCompletion, message);
    }

    private void invalidatePrompt(
            Minecraft client,
            LockPromptScreen prompt,
            LockPromptCompletion completion,
            String message
    ) {
        if (prompt == null || completion == null || prompt != activeScreen
                || client.gui.screen() != prompt) {
            return;
        }

        // Report the terminal error before closing. The screen callback is still
        // guarded by the old screen identity, so it cannot affect a later prompt.
        try {
            completion.error(message);
        } catch (RuntimeException ignored) {
            // Closing the prompt remains fail-closed if a completion callback fails.
        }
        prompt.showError(message);
        dismiss(client, prompt, true);
    }

    private void dismissCurrent(Minecraft client, boolean sendCancellation) {
        dismiss(client, activeScreen, sendCancellation);
    }

    private void dismiss(Minecraft client, LockPromptScreen prompt, boolean sendCancellation) {
        if (prompt == null) {
            return;
        }
        if (prompt != activeScreen) {
            return;
        }

        UUID token = pendingContextToken != null ? pendingContextToken : activeContextToken;
        Connection promptConnection = connection;
        Screen restore = previousScreen;
        activeScreen = null;
        previousScreen = null;
        clearPromptTokens();
        connection = null;
        if (sendCancellation && token != null && !cancelSent && promptConnection != null
                && isCurrentConnection(client, promptConnection)) {
            cancelSent = true;
            try {
                if (ClientPlayNetworking.canSend(LockPromptCancelPayload.TYPE)) {
                    ClientPlayNetworking.send(new LockPromptCancelPayload(token));
                }
            } catch (RuntimeException ignored) {
                // The local dismissal remains fail-closed if the connection is already gone.
            }
        }
        if (client.gui.screen() == prompt) {
            client.gui.setScreen(restore);
        }
    }

    private void restoreCurrent(Minecraft client) {
        LockPromptScreen prompt = activeScreen;
        if (prompt == null) {
            return;
        }
        restore(client, prompt, previousScreen);
    }

    private void restore(Minecraft client, LockPromptScreen prompt, Screen restore) {
        if (activeScreen == prompt) {
            activeScreen = null;
            previousScreen = null;
            clearPromptTokens();
            connection = null;
        }
        if (client.gui.screen() == prompt) {
            client.gui.setScreen(restore);
        }
    }

    private void clearPromptTokens() {
        activeContextToken = null;
        pendingContextToken = null;
        pendingCompletion = null;
        pendingRequestTicks = 0;
    }

    private boolean isCurrentConnection(Minecraft client, Connection packetConnection) {
        return packetConnection != null
                && client.getConnection() != null
                && client.getConnection().getConnection() == packetConnection;
    }
}
