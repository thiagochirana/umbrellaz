package dev.chirana.umbrellaz.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldSkyModeTest {
    @Test
    void mapsCommandsToExpectedWeatherLevels() {
        assertEquals(0.0F, WorldSkyMode.CLEAN.rainLevel());
        assertEquals(0.0F, WorldSkyMode.CLEAN.thunderLevel());
        assertEquals(1.0F, WorldSkyMode.RAIN.rainLevel());
        assertEquals(0.0F, WorldSkyMode.RAIN.thunderLevel());
        assertEquals(1.0F, WorldSkyMode.STORM.rainLevel());
        assertEquals(1.0F, WorldSkyMode.STORM.thunderLevel());
    }
}
