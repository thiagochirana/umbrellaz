package dev.chirana.umbrellaz.mixin;

import java.util.Locale;

final class CommandAuditPath {
    private static final int MAX_LITERAL_NODES = 4;
    private static final int MAX_PATH_LENGTH = 128;
    private static final String UNKNOWN = "unknown";

    private CommandAuditPath() {
    }

    static String bounded(Iterable<String> literalNames) {
        if (literalNames == null) {
            return UNKNOWN;
        }
        StringBuilder path = new StringBuilder();
        int names = 0;
        for (String literalName : literalNames) {
            if (names++ == MAX_LITERAL_NODES) {
                break;
            }
            String normalized = normalize(literalName);
            if (normalized == null) {
                return UNKNOWN;
            }
            int additionalLength = normalized.length() + (path.isEmpty() ? 0 : 1);
            if (path.length() + additionalLength > MAX_PATH_LENGTH) {
                break;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(normalized);
        }
        return path.isEmpty() ? UNKNOWN : path.toString();
    }

    private static String normalize(String literalName) {
        if (literalName == null || literalName.isEmpty()) {
            return null;
        }
        String normalized = literalName.toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9][a-z0-9._:-]*") ? normalized : null;
    }
}
