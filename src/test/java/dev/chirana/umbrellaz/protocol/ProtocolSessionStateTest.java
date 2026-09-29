package dev.chirana.umbrellaz.protocol;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSessionStateTest {
    private static final Set<Integer> SERVER_FEATURES = Set.of(ProtocolConstants.FEATURE_LOCK_GUI);

    @Test
    void waitsBeforeDeadlineAndTimesOutAtDeadline() {
        ProtocolSessionState session = session(20);

        assertFalse(session.expireIfDue(19));
        assertEquals(ProtocolSessionState.State.UNNEGOTIATED, session.state());
        assertTrue(session.expireIfDue(20));
        assertEquals(ProtocolSessionState.State.TIMED_OUT, session.state());
    }

    @Test
    void acceptsCompatibleHelloAndIdenticalDuplicate() {
        ProtocolSessionState session = session(20);

        assertEquals(ProtocolSessionState.HelloResult.ACCEPTED,
                session.receiveHello(19, ProtocolConstants.CURRENT_VERSION, session.nonce(), session.generation(),
                        new int[]{ProtocolConstants.FEATURE_LOCK_GUI}));
        assertEquals(ProtocolSessionState.State.COMPATIBLE, session.state());
        assertEquals(ProtocolSessionState.HelloResult.DUPLICATE,
                session.receiveHello(19, ProtocolConstants.CURRENT_VERSION, session.nonce(), session.generation(),
                        new int[]{ProtocolConstants.FEATURE_LOCK_GUI}));
    }

    @Test
    void rejectsWrongVersionNonceGenerationUnknownAndMissingMandatoryFeature() {
        assertRejected((session) -> session.receiveHello(1, ProtocolConstants.CURRENT_VERSION + 1,
                session.nonce(), session.generation(), new int[]{ProtocolConstants.FEATURE_LOCK_GUI}));
        assertRejected((session) -> session.receiveHello(1, ProtocolConstants.CURRENT_VERSION,
                UUID.randomUUID(), session.generation(), new int[]{ProtocolConstants.FEATURE_LOCK_GUI}));
        assertRejected((session) -> session.receiveHello(1, ProtocolConstants.CURRENT_VERSION,
                session.nonce(), session.generation() + 1, new int[]{ProtocolConstants.FEATURE_LOCK_GUI}));
        assertRejected((session) -> session.receiveHello(1, ProtocolConstants.CURRENT_VERSION,
                session.nonce(), session.generation(), new int[]{99}));
        assertRejected((session) -> session.receiveHello(1, ProtocolConstants.CURRENT_VERSION,
                session.nonce(), session.generation(), new int[0]));
    }

    @Test
    void rejectsAlteredDuplicate() {
        ProtocolSessionState session = session(20);
        assertEquals(ProtocolSessionState.HelloResult.ACCEPTED,
                session.receiveHello(1, 1, session.nonce(), session.generation(), new int[]{1}));

        assertEquals(ProtocolSessionState.HelloResult.REJECTED,
                session.receiveHello(1, 1, session.nonce(), session.generation() + 1, new int[]{1}));
        assertEquals(ProtocolSessionState.State.INCOMPATIBLE, session.state());
    }

    @Test
    void disconnectInvalidatesSessionAndCannotBeReopened() {
        ProtocolSessionState session = session(20);
        assertEquals(ProtocolSessionState.HelloResult.ACCEPTED,
                session.receiveHello(1, 1, session.nonce(), session.generation(), new int[]{1}));

        session.invalidate();

        assertEquals(ProtocolSessionState.State.DISCONNECTED, session.state());
        assertEquals(ProtocolSessionState.HelloResult.REJECTED,
                session.receiveHello(1, 1, session.nonce(), session.generation(), new int[]{1}));
    }

    private static ProtocolSessionState session(long deadline) {
        return new ProtocolSessionState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7,
                deadline, SERVER_FEATURES);
    }

    private static void assertRejected(HelloAttempt attempt) {
        ProtocolSessionState session = session(20);
        assertEquals(ProtocolSessionState.HelloResult.REJECTED, attempt.run(session));
        assertEquals(ProtocolSessionState.State.INCOMPATIBLE, session.state());
    }

    @FunctionalInterface
    private interface HelloAttempt {
        ProtocolSessionState.HelloResult run(ProtocolSessionState session);
    }
}
