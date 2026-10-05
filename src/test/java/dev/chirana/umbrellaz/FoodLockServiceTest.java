package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.player.FoodLockService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodLockServiceTest {
    @Test
    void hasNoLockedFoodInitially() {
        FoodLockService service = new FoodLockService();

        assertFalse(service.lockedFood(UUID.randomUUID()).isPresent());
    }

    @Test
    void preservesTheMinecraftFoodBoundaries() {
        FoodLockService service = new FoodLockService();
        UUID emptyPlayer = UUID.randomUUID();
        UUID fullPlayer = UUID.randomUUID();

        service.lock(emptyPlayer, 0);
        service.lock(fullPlayer, 20);

        assertEquals(0, service.lockedFood(emptyPlayer).orElseThrow());
        assertEquals(20, service.lockedFood(fullPlayer).orElseThrow());
    }

    @Test
    void isolatesPlayersAndSupportsUnlockAndClear() {
        FoodLockService service = new FoodLockService();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();

        service.lock(firstPlayer, 7);
        service.lock(secondPlayer, 13);
        service.unlock(firstPlayer);
        assertFalse(service.lockedFood(firstPlayer).isPresent());
        assertEquals(13, service.lockedFood(secondPlayer).orElseThrow());

        service.clear(secondPlayer);
        assertFalse(service.lockedFood(secondPlayer).isPresent());
        assertTrue(service.lockedFood(firstPlayer).isEmpty());
    }
}
