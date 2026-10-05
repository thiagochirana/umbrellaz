package dev.chirana.umbrellaz.protocol;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class ProtocolSessionManager {
    private final Map<ServerPlayer, Session> sessions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong generations = new AtomicLong();
    private final Set<Integer> supportedFeatures;
    private final ActionContextRegistry actionContexts = new ActionContextRegistry();

    public ProtocolSessionManager(Set<Integer> supportedFeatures) {
        if (supportedFeatures == null || supportedFeatures.size() > ProtocolConstants.MAX_FEATURES
                || supportedFeatures.stream().anyMatch(feature -> feature < 0
                || feature > ProtocolConstants.MAX_FEATURE_ID)
                || !supportedFeatures.containsAll(ProtocolConstants.MANDATORY_FEATURES)) {
            throw new IllegalArgumentException("Invalid or incomplete protocol feature set");
        }
        this.supportedFeatures = Set.copyOf(supportedFeatures);
    }

    public ActionContextRegistry actionContexts() {
        return actionContexts;
    }

    public void onJoin(ServerPlayer player, MinecraftServer server) {
        long generation = generations.incrementAndGet();
        UUID connectionId = distinctConnectionId(player.getUUID());
        ProtocolSessionState protocolState = new ProtocolSessionState(player.getUUID(), connectionId, UUID.randomUUID(),
                generation, server.getTickCount() + ProtocolConstants.HANDSHAKE_DEADLINE_TICKS, supportedFeatures);
        Session session = new Session(player, protocolState);
        Session previous = sessions.put(player, session);
        if (previous != null) {
            previous.invalidate();
            actionContexts.invalidateConnection(previous.connectionId());
        }

        if (!ServerPlayNetworking.canSend(player, UmbrellazHelloPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new UmbrellazHelloPayload(ProtocolConstants.CURRENT_VERSION,
                session.nonce(), generation, supportedFeatures.stream().mapToInt(Integer::intValue).sorted().toArray()));
    }

    public void receiveHello(UmbrellazHelloResponsePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        Session session = sessionFor(player);
        if (session == null || session.player() != player || !session.uuid().equals(player.getUUID())) {
            disconnect(context.server(), player, "Não foi possível validar a conexão do Umbrellaz.");
            return;
        }

        ProtocolSessionState.HelloResult result = session.protocolState().receiveHello(context.server().getTickCount(),
                payload.protocolVersion(), payload.nonce(), payload.generation(), payload.featureIds());
        if (result == ProtocolSessionState.HelloResult.ACCEPTED
                || result == ProtocolSessionState.HelloResult.DUPLICATE) {
            return;
        }
        disconnect(context.server(), player,
                result == ProtocolSessionState.HelloResult.TIMED_OUT
                        ? "O cliente Umbrellaz é obrigatório para entrar neste servidor."
                        : "O cliente Umbrellaz é incompatível com este servidor.");
    }

    public boolean isCompatible(ServerPlayer player) {
        Session session = sessionFor(player);
        return session != null && session.player() == player && session.uuid().equals(player.getUUID())
                && session.state() == State.COMPATIBLE;
    }

    public Optional<ActionContextRegistry.Context> openContext(ServerPlayer player, String action, String target,
                                                               Duration lifetime) {
        Session session = compatibleSession(player);
        if (session == null) return Optional.empty();
        return Optional.of(actionContexts.open(session.connectionId(), session.uuid(), session.nonce(),
                session.generation(), action, target, lifetime));
    }

    public Optional<ActionContextRegistry.Context> claimContext(ServerPlayer player, UUID token) {
        Session session = compatibleSession(player);
        if (session == null) return Optional.empty();
        return actionContexts.claim(token, session.connectionId(), session.uuid(), session.nonce(),
                session.generation());
    }

    public void tick(MinecraftServer server) {
        for (Session session : snapshot()) {
            if (session.player().level().getServer() != server) continue;
            if (session.protocolState().expireIfDue(server.getTickCount())) {
                disconnect(server, session.player(), "O cliente Umbrellaz é obrigatório para entrar neste servidor.");
            }
        }
    }

    public void onDisconnect(ServerPlayer player) {
        Session session = sessions.remove(player);
        if (session != null) {
            session.invalidate();
            actionContexts.invalidateConnection(session.connectionId());
        }
    }

    public void shutdown() {
        for (Session session : snapshot()) {
            session.invalidate();
            actionContexts.invalidateConnection(session.connectionId());
        }
        sessions.clear();
        actionContexts.invalidateAll();
    }

    public static Optional<Set<Integer>> negotiateCapabilities(int protocolVersion, int expectedVersion,
                                                                int[] clientFeatures,
                                                                Set<Integer> serverFeatures,
                                                                Set<Integer> mandatoryFeatures) {
        return ProtocolSessionState.negotiateCapabilities(protocolVersion, expectedVersion, clientFeatures,
                serverFeatures, mandatoryFeatures);
    }

    private Session compatibleSession(ServerPlayer player) {
        Session session = sessionFor(player);
        if (session == null || session.player() != player || !session.uuid().equals(player.getUUID())
                || session.state() != State.COMPATIBLE) {
            return null;
        }
        return session;
    }

    private Session sessionFor(ServerPlayer player) {
        synchronized (sessions) {
            return sessions.get(player);
        }
    }

    private Session[] snapshot() {
        synchronized (sessions) {
            return sessions.values().toArray(Session[]::new);
        }
    }

    private static UUID distinctConnectionId(UUID playerUuid) {
        UUID connectionId;
        do {
            connectionId = UUID.randomUUID();
        } while (connectionId.equals(playerUuid));
        return connectionId;
    }

    private void disconnect(MinecraftServer server, ServerPlayer player, String reason) {
        server.execute(() -> {
            if (!player.hasDisconnected()) {
                player.connection.disconnect(Component.literal(reason));
            }
        });
    }

    public enum State { UNNEGOTIATED, COMPATIBLE, INCOMPATIBLE, TIMED_OUT, DISCONNECTED }

    public static final class Session {
        private final ServerPlayer player;
        private final ProtocolSessionState protocolState;

        private Session(ServerPlayer player, ProtocolSessionState protocolState) {
            this.player = player;
            this.protocolState = protocolState;
        }

        public ServerPlayer player() { return player; }
        public UUID uuid() { return protocolState.playerUuid(); }
        public UUID connectionId() { return protocolState.connectionId(); }
        public UUID nonce() { return protocolState.nonce(); }
        public long generation() { return protocolState.generation(); }
        public long deadlineTick() { return protocolState.deadlineTick(); }
        public State state() { return State.valueOf(protocolState.state().name()); }
        public int negotiatedVersion() { return protocolState.negotiatedVersion(); }
        public Set<Integer> negotiatedFeatures() { return protocolState.negotiatedFeatures(); }
        private ProtocolSessionState protocolState() { return protocolState; }
        private void invalidate() { protocolState.invalidate(); }
    }
}
