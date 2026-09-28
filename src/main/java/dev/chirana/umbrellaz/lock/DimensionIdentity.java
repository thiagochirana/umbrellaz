package dev.chirana.umbrellaz.lock;

import java.util.Objects;

public record DimensionIdentity(String value) {
    public DimensionIdentity {
        Objects.requireNonNull(value, "dimension identity");
        if (value.isBlank()) {
            throw new IllegalArgumentException("dimension identity must not be blank");
        }
    }
}
