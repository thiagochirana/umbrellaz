package dev.chirana.umbrellaz.player;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AliasRules {
    private static final Pattern VALID_ALIAS = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private AliasRules() {
    }

    public static Optional<String> canonicalize(String alias) {
        if (alias == null || !VALID_ALIAS.matcher(alias).matches()) {
            return Optional.empty();
        }
        String canonical = alias.toLowerCase(Locale.ROOT);
        return canonical.equals("self") ? Optional.empty() : Optional.of(canonical);
    }

    public static boolean isReserved(String alias) {
        return alias != null && alias.equalsIgnoreCase("self");
    }
}
