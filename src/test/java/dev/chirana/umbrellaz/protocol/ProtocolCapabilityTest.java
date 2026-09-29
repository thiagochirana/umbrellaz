package dev.chirana.umbrellaz.protocol;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolCapabilityTest {
    private static final Set<Integer> SERVER_FEATURES = Set.of(ProtocolConstants.FEATURE_LOCK_GUI);
    private static final Set<Integer> MANDATORY = Set.of(ProtocolConstants.FEATURE_LOCK_GUI);

    @Test
    void acceptsVersionAndMandatoryFeature() {
        assertEquals(Set.of(ProtocolConstants.FEATURE_LOCK_GUI),
                ProtocolSessionManager.negotiateCapabilities(1, 1,
                        new int[]{ProtocolConstants.FEATURE_LOCK_GUI}, SERVER_FEATURES, MANDATORY).orElseThrow());
    }

    @Test
    void rejectsWrongVersionUnknownOrMissingFeatures() {
        assertTrue(ProtocolSessionManager.negotiateCapabilities(2, 1, new int[]{1},
                SERVER_FEATURES, MANDATORY).isEmpty());
        assertTrue(ProtocolSessionManager.negotiateCapabilities(1, 1, new int[]{99},
                SERVER_FEATURES, MANDATORY).isEmpty());
        assertTrue(ProtocolSessionManager.negotiateCapabilities(1, 1, new int[0],
                SERVER_FEATURES, MANDATORY).isEmpty());
    }

    @Test
    void rejectsDuplicateFeatures() {
        assertTrue(ProtocolSessionManager.negotiateCapabilities(1, 1,
                new int[]{ProtocolConstants.FEATURE_LOCK_GUI, ProtocolConstants.FEATURE_LOCK_GUI},
                SERVER_FEATURES, MANDATORY).isEmpty());
    }
}
