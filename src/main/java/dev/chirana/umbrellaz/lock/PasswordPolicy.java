package dev.chirana.umbrellaz.lock;

import java.util.Locale;
import java.util.Objects;

public final class PasswordPolicy {
    public boolean isValid(String password) {
        return password != null && password.length() == 4
                && isAsciiLetter(password.charAt(0))
                && isAsciiDigit(password.charAt(1))
                && isAsciiDigit(password.charAt(2))
                && isAsciiDigit(password.charAt(3));
    }

    public String canonicalize(String password) {
        Objects.requireNonNull(password, "password");
        if (!isValid(password)) {
            throw new IllegalArgumentException("Password must contain one ASCII letter followed by three ASCII digits");
        }
        return password.substring(0, 1).toUpperCase(Locale.ROOT) + password.substring(1);
    }

    public String requireCanonical(String password) {
        return canonicalize(password);
    }

    private boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }
}
