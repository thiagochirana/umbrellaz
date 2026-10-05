package dev.chirana.umbrellaz.audit;

import java.util.regex.Pattern;

final class AuditValidation {
    private static final Pattern STABLE_CODE = Pattern.compile("[a-z0-9][a-z0-9._:-]*");
    private static final Pattern TARGET_IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._:/,-]*");

    private AuditValidation() {
    }

    static String requiredText(String value, int maximumLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        rejectControlCharacters(value, field);
        return value;
    }

    static String stableCode(String value, int maximumLength, String field) {
        requiredText(value, maximumLength, field);
        if (!STABLE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a stable code: " + value);
        }
        return value;
    }

    static String targetIdentifier(String value, int maximumLength, String field) {
        requiredText(value, maximumLength, field);
        if (!TARGET_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a stable identifier: " + value);
        }
        return value;
    }

    static void optionalDisplayName(String value, int maximumLength, String field) {
        if (value != null) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank when present");
            }
            if (value.length() > maximumLength) {
                throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
            }
            rejectControlCharacters(value, field);
        }
    }

    static void rejectControlCharacters(String value, String field) {
        rejectUnpairedSurrogates(value, field);
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
    }

    static void rejectUnpairedSurrogates(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " must not contain unpaired UTF-16 surrogates");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " must not contain unpaired UTF-16 surrogates");
            }
        }
    }
}
