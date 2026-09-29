package dev.chirana.umbrellaz;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chirana.umbrellaz.gui.LockPromptMode;
import dev.chirana.umbrellaz.gui.LockPromptScreen;
import dev.chirana.umbrellaz.protocol.UmbrellazHelloPayload;
import dev.chirana.umbrellaz.protocol.UmbrellazHelloResponsePayload;
import dev.chirana.umbrellaz.protocol.LockPromptOpenPayload;
import dev.chirana.umbrellaz.protocol.LockPromptResultPayload;
import dev.chirana.umbrellaz.protocol.ProtocolConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class UmbrellazClient implements ClientModInitializer {

    public static final int IMPLEMENTED_PROTOCOL_VERSION = 1;
    public static final int DEBUG_CREATE_PASSWORD_KEY = InputConstants.KEY_F8;
    public static final int DEBUG_CONFIRM_PASSWORD_KEY = InputConstants.KEY_F9;
    public static final Set<Integer> IMPLEMENTED_FEATURES = Set.of(
            ProtocolConstants.FEATURE_LOCK_GUI);

    private static UUID respondedNonce;
    private static long respondedGeneration = -1L;
    private static KeyMapping debugCreatePassword;
    private static KeyMapping debugConfirmPassword;
    private final LockPromptController lockPromptController = new LockPromptController();

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(UmbrellazHelloPayload.TYPE,
                (payload, context) -> context.client().execute(() -> respondToHello(payload, context)));
        ClientPlayNetworking.registerGlobalReceiver(LockPromptOpenPayload.TYPE,
                (payload, context) -> dispatchOpen(payload, context));
        ClientPlayNetworking.registerGlobalReceiver(LockPromptResultPayload.TYPE,
                (payload, context) -> dispatchResult(payload, context));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> {
                    if (client.getConnection() == null
                            || client.getConnection().getConnection() == handler.getConnection()) {
                        clearHandshakeState();
                    }
                    lockPromptController.onDisconnect(client, handler.getConnection());
                }));
        debugCreatePassword = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "Debug Umbrellaz: criar senha", DEBUG_CREATE_PASSWORD_KEY, KeyMapping.Category.DEBUG));
        debugConfirmPassword = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "Debug Umbrellaz: confirmar senha", DEBUG_CONFIRM_PASSWORD_KEY, KeyMapping.Category.DEBUG));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            lockPromptController.tick(client);
            openDebugPrompt(client);
        });
    }

    private void dispatchOpen(LockPromptOpenPayload payload, ClientPlayNetworking.Context context) {
        Connection connection = context.packetContext().get(PacketContext.CONNECTION);
        context.client().execute(() -> lockPromptController.receiveOpen(context.client(), payload, connection));
    }

    private void dispatchResult(LockPromptResultPayload payload, ClientPlayNetworking.Context context) {
        Connection connection = context.packetContext().get(PacketContext.CONNECTION);
        context.client().execute(() -> lockPromptController.receiveResult(context.client(), payload, connection));
    }

    private static void respondToHello(UmbrellazHelloPayload payload, ClientPlayNetworking.Context context) {
        if (payload.nonce().equals(respondedNonce) && payload.generation() == respondedGeneration) {
            return;
        }
        respondedNonce = payload.nonce();
        respondedGeneration = payload.generation();
        int[] negotiatedFeatures = Arrays.stream(payload.featureIds())
                .filter(IMPLEMENTED_FEATURES::contains)
                .distinct()
                .sorted()
                .toArray();
        context.responseSender().sendPacket(new UmbrellazHelloResponsePayload(
                IMPLEMENTED_PROTOCOL_VERSION, payload.nonce(), payload.generation(), negotiatedFeatures));
    }

    private static void clearHandshakeState() {
        respondedNonce = null;
        respondedGeneration = -1L;
    }

    private static void openDebugPrompt(Minecraft client) {
        if (debugCreatePassword.consumeClick()) {
            openDebugPrompt(client, LockPromptMode.CREATE_PASSWORD);
        }
        if (debugConfirmPassword.consumeClick()) {
            openDebugPrompt(client, LockPromptMode.CONFIRM_PASSWORD);
        }
    }

    private static void openDebugPrompt(Minecraft client, LockPromptMode mode) {
        Screen previous = client.gui.screen();
        if (previous instanceof LockPromptScreen) {
            return;
        }
        Runnable dismiss = () -> client.gui.setScreen(previous);
        client.gui.setScreen(new LockPromptScreen(
                mode,
                (submittedMode, password, completion) -> CompletableFuture
                        .delayedExecutor(650, TimeUnit.MILLISECONDS)
                        .execute(() -> completion.error("Prévia local: pedido não enviado.")),
                dismiss,
                dismiss));
    }
}
