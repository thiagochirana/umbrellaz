package dev.chirana.umbrellaz.player;

public final class FoodLevelArithmetic {
    public static final int MIN_FOOD_LEVEL = 0;
    public static final int MAX_FOOD_LEVEL = 20;

    private FoodLevelArithmetic() {
    }

    public static int add(int current, int amount) {
        return clamp((long) current + amount);
    }

    public static int remove(int current, int amount) {
        return clamp((long) current - amount);
    }

    private static int clamp(long value) {
        return (int) Math.max(MIN_FOOD_LEVEL, Math.min(MAX_FOOD_LEVEL, value));
    }
}
