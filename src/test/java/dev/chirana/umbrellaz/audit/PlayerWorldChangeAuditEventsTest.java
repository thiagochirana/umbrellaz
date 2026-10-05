package dev.chirana.umbrellaz.audit;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerWorldChangeAuditEventsTest {
    @Test
    void requestContainsConfirmedDestinationAndNoInitiatorClaim() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AuditRecordRequest request = PlayerWorldChangeAuditEvents.request(player, "Alice",
                "minecraft:overworld", "minecraft:the_nether", 12, 64, -3);
        assertEquals(AuditActions.PLAYER_WORLD_CHANGED, request.action());
        assertEquals("system", request.source().code());
        assertEquals("12,64,-3", request.payload().values().get("target_position"));
        assertEquals("minecraft:overworld", request.payload().values().get("source_world"));
        assertEquals("minecraft:the_nether", request.payload().values().get("target_world"));
        assertEquals("Alice", request.payload().values().get("player_name"));
    }
}
