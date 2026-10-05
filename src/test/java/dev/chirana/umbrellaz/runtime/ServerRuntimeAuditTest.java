package dev.chirana.umbrellaz.runtime;

import dev.chirana.umbrellaz.audit.ActorType;
import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditDelivery;
import dev.chirana.umbrellaz.audit.Outcome;
import dev.chirana.umbrellaz.audit.Source;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerRuntimeAuditTest {
    private static final UUID PLAYER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void playerLifecycleRequestsUseTrustedIdentityAndBestEffortDelivery() {
        var joined = ServerRuntime.playerJoinedAuditRequest(PLAYER_UUID, "Ada");
        var left = ServerRuntime.playerLeftAuditRequest(PLAYER_UUID, "Ada");

        assertEquals(Source.EVENT, joined.source());
        assertEquals(Outcome.SUCCESS, joined.outcome());
        assertEquals(AuditDelivery.BEST_EFFORT, joined.delivery());
        assertEquals(ActorType.PLAYER, joined.context().actor().type());
        assertEquals(PLAYER_UUID, joined.context().actor().uuid());
        assertEquals("player", joined.target().type());
        assertEquals(PLAYER_UUID.toString(), joined.target().id());
        assertEquals("joined", joined.payload().values().get("result"));
        assertEquals(AuditActions.LIFECYCLE_LEFT, left.action());
        assertEquals("player_disconnected", left.reasonCode());
    }

    @Test
    void serverReadyRequestIsNotAnAuthorizationEvent() {
        var request = ServerRuntime.serverStartedAuditRequest();

        assertEquals(AuditActions.SYSTEM_STARTED, request.action());
        assertEquals(Source.SYSTEM, request.source());
        assertEquals(Outcome.SUCCESS, request.outcome());
        assertEquals(AuditDelivery.BEST_EFFORT, request.delivery());
        assertEquals(ActorType.SYSTEM, request.context().actor().type());
        assertNull(request.target());
    }
}
