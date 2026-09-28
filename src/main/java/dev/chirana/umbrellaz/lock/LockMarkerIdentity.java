package dev.chirana.umbrellaz.lock;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class LockMarkerIdentity {
    static final String MARKER_TAG = "umbrellaz:lock_marker";
    private static final String LOCKER_TAG_PREFIX = "umbrellaz:locker=";

    private LockMarkerIdentity() {
    }

    static String lockerTag(UUID lockerId) {
        return LOCKER_TAG_PREFIX + lockerId;
    }

    static boolean isMarker(Set<String> tags) {
        return tags.contains(MARKER_TAG) && lockerId(tags).isPresent();
    }

    static Optional<UUID> lockerId(Set<String> tags) {
        return tags.stream()
                .filter(tag -> tag.startsWith(LOCKER_TAG_PREFIX))
                .map(tag -> tag.substring(LOCKER_TAG_PREFIX.length()))
                .map(LockMarkerIdentity::parse)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<UUID> parse(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
