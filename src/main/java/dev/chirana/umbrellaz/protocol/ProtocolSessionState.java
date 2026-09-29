package dev.chirana.umbrellaz.protocol;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Minecraft-independent state for one physical protocol connection.
 */
public final class ProtocolSessionState {
    private final UUID playerUuid;
    private final UUID connectionId;
    private final UUID nonce;
    private final long generation;
    private final long deadlineTick;
    private final Set<Integer> supportedFeatures;

    private State state = State.UNNEGOTIATED;
    private int negotiatedVersion = -1;
    private Set<Integer> negotiatedFeatures = Set.of();

    public ProtocolSessionState(UUID playerUuid, UUID connectionId, UUID nonce, long generation,
                                long deadlineTick, Set<Integer> supportedFeatures) {
        if (playerUuid == null || connectionId == null || playerUuid.equals(connectionId) || nonce == null
                || generation < 0 || deadlineTick < 0 || supportedFeatures == null
                || supportedFeatures.size() > ProtocolConstants.MAX_FEATURES
                || supportedFeatures.stream().anyMatch(feature -> feature < 0
                || feature > ProtocolConstants.MAX_FEATURE_ID)
                || !supportedFeatures.containsAll(ProtocolConstants.MANDATORY_FEATURES)) {
            throw new IllegalArgumentException("Invalid protocol session");
        }
        this.playerUuid = playerUuid;
        this.connectionId = connectionId;
        this.nonce = nonce;
        this.generation = generation;
        this.deadlineTick = deadlineTick;
        this.supportedFeatures = Set.copyOf(supportedFeatures);
    }

    public synchronized HelloResult receiveHello(long currentTick, int protocolVersion, UUID responseNonce,
                                                  long responseGeneration, int[] clientFeatures) {
        if (state == State.UNNEGOTIATED) {
            if (currentTick >= deadlineTick) {
                state = State.TIMED_OUT;
                return HelloResult.TIMED_OUT;
            }
            Optional<Set<Integer>> negotiated = negotiateCapabilities(protocolVersion,
                    ProtocolConstants.CURRENT_VERSION, clientFeatures, supportedFeatures,
                    ProtocolConstants.MANDATORY_FEATURES);
            if (nonce.equals(responseNonce) && generation == responseGeneration && negotiated.isPresent()) {
                negotiatedVersion = protocolVersion;
                negotiatedFeatures = negotiated.get();
                state = State.COMPATIBLE;
                return HelloResult.ACCEPTED;
            }
            state = State.INCOMPATIBLE;
            return HelloResult.REJECTED;
        }

        if (state == State.COMPATIBLE
                && protocolVersion == negotiatedVersion
                && nonce.equals(responseNonce)
                && generation == responseGeneration
                && sameFeatures(clientFeatures, negotiatedFeatures)) {
            return HelloResult.DUPLICATE;
        }

        if (state != State.DISCONNECTED) {
            state = State.INCOMPATIBLE;
        }
        return HelloResult.REJECTED;
    }

    public synchronized boolean expireIfDue(long currentTick) {
        if (state == State.UNNEGOTIATED && currentTick >= deadlineTick) {
            state = State.TIMED_OUT;
            return true;
        }
        return false;
    }

    public synchronized void invalidate() {
        state = State.DISCONNECTED;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public UUID connectionId() {
        return connectionId;
    }

    public UUID nonce() {
        return nonce;
    }

    public long generation() {
        return generation;
    }

    public long deadlineTick() {
        return deadlineTick;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int negotiatedVersion() {
        return negotiatedVersion;
    }

    public synchronized Set<Integer> negotiatedFeatures() {
        return negotiatedFeatures;
    }

    public static Optional<Set<Integer>> negotiateCapabilities(int protocolVersion, int expectedVersion,
                                                                int[] clientFeatures,
                                                                Set<Integer> serverFeatures,
                                                                Set<Integer> mandatoryFeatures) {
        if (protocolVersion != expectedVersion || clientFeatures == null || serverFeatures == null
                || mandatoryFeatures == null || clientFeatures.length > ProtocolConstants.MAX_FEATURES) {
            return Optional.empty();
        }
        Set<Integer> offered = new HashSet<>();
        for (int feature : clientFeatures) {
            if (feature < 0 || feature > ProtocolConstants.MAX_FEATURE_ID || !offered.add(feature)
                    || !serverFeatures.contains(feature)) {
                return Optional.empty();
            }
        }
        if (!offered.containsAll(mandatoryFeatures)) return Optional.empty();
        return Optional.of(Set.copyOf(offered));
    }

    private static boolean sameFeatures(int[] features, Set<Integer> expected) {
        return negotiateCapabilities(ProtocolConstants.CURRENT_VERSION, ProtocolConstants.CURRENT_VERSION,
                features, expected, expected).map(expected::equals).orElse(false);
    }

    public enum HelloResult { ACCEPTED, DUPLICATE, REJECTED, TIMED_OUT }

    public enum State { UNNEGOTIATED, COMPATIBLE, INCOMPATIBLE, TIMED_OUT, DISCONNECTED }
}
