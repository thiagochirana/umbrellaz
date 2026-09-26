package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.player.HealthLockService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthLockServiceTest {
    @Test
    void locksAndUnlocksRuntimeHealthByUuid() {
        HealthLockService service = new HealthLockService();
        UUID playerUuid = UUID.randomUUID();

        assertFalse(service.lockedHealth(playerUuid).isPresent());
        service.lock(playerUuid, 7.5f);
        assertTrue(service.lockedHealth(playerUuid).isPresent());
        assertEquals(7.5f, service.lockedHealth(playerUuid).orElseThrow());

        service.unlock(playerUuid);
        assertFalse(service.lockedHealth(playerUuid).isPresent());
    }
}
