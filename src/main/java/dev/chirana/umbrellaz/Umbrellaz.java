package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.auth.AuthEvents;
import dev.chirana.umbrellaz.blocks.BlocksEvents;
import dev.chirana.umbrellaz.lock.LockEvents;
import dev.chirana.umbrellaz.protocol.LockPromptCancelPayload;
import dev.chirana.umbrellaz.protocol.LockPromptOpenPayload;
import dev.chirana.umbrellaz.protocol.LockPromptResultPayload;
import dev.chirana.umbrellaz.protocol.LockPromptSubmitPayload;
import dev.chirana.umbrellaz.protocol.UmbrellazHelloPayload;
import dev.chirana.umbrellaz.protocol.UmbrellazHelloResponsePayload;
import dev.chirana.umbrellaz.runtime.ServerRuntimeFactory;
import dev.chirana.umbrellaz.runtime.ServerRuntimeRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Umbrellaz implements ModInitializer {
    public static final String MOD_ID = "umbrellaz";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public void onInitialize() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        AuthEvents.installGlobalCallbacks();
        BlocksEvents.installGlobalCallbacks();
        LockEvents.installGlobalCallbacks();
        PayloadTypeRegistry.clientboundPlay().register(UmbrellazHelloPayload.TYPE, UmbrellazHelloPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LockPromptOpenPayload.TYPE, LockPromptOpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LockPromptResultPayload.TYPE, LockPromptResultPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UmbrellazHelloResponsePayload.TYPE,
                UmbrellazHelloResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LockPromptSubmitPayload.TYPE, LockPromptSubmitPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LockPromptCancelPayload.TYPE, LockPromptCancelPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(UmbrellazHelloResponsePayload.TYPE,
                (payload, context) -> ServerRuntimeRegistry.find(context.server())
                        .ifPresent(runtime -> runtime.protocol().receiveHello(payload, context)));
        ServerPlayNetworking.registerGlobalReceiver(LockPromptSubmitPayload.TYPE,
                (payload, context) -> ServerRuntimeRegistry.findReady(context.server())
                        .ifPresent(runtime -> runtime.lockEvents().handlePromptSubmit(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(LockPromptCancelPayload.TYPE,
                (payload, context) -> ServerRuntimeRegistry.findReady(context.server())
                        .ifPresent(runtime -> runtime.lockEvents().handlePromptCancel(context.player(), payload)));

        ServerRuntimeFactory factory = new ServerRuntimeFactory();
        ServerLifecycleEvents.SERVER_STARTING.register(factory::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(factory::stop);
        ServerTickEvents.END_SERVER_TICK.register(server ->
                ServerRuntimeRegistry.find(server).ifPresent(runtime -> runtime.tick()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            ServerRuntimeRegistry.find(server).ifPresentOrElse(runtime -> runtime.onJoin(player),
                    () -> player.connection.disconnect(Component.literal("Umbrellaz is still starting.")));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ServerRuntimeRegistry.find(server).ifPresent(runtime -> runtime.onDisconnect(handler.player)));
        LOGGER.info("Umbrellaz global adapters registered");
    }
}
