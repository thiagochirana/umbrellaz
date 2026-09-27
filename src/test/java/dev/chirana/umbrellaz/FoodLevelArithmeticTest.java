package dev.chirana.umbrellaz;

import dev.chirana.umbrellaz.player.FoodLevelArithmetic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodLevelArithmeticTest {
    @Test
    void clampsFoodLevelChangesToTheMinecraftRange() {
        assertEquals(20, FoodLevelArithmetic.add(18, 5));
        assertEquals(0, FoodLevelArithmetic.remove(2, 5));
        assertEquals(20, FoodLevelArithmetic.add(20, Integer.MAX_VALUE));
        assertEquals(0, FoodLevelArithmetic.remove(0, Integer.MAX_VALUE));
    }

    @Test
    void preservesChangesInsideTheMinecraftRange() {
        assertEquals(13, FoodLevelArithmetic.add(8, 5));
        assertEquals(3, FoodLevelArithmetic.remove(8, 5));
    }
}
