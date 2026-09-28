package dev.chirana.umbrellaz.lock;

import java.util.Objects;

public record WorldIdentity(String value) {
    public WorldIdentity {
        value = requireText(value, "world identity");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
