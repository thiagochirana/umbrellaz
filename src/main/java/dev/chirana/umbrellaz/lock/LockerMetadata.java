package dev.chirana.umbrellaz.lock;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record LockerMetadata(UUID lockerId, UUID ownerUuid, PasswordHash password, Instant createdAt,
                             Instant updatedAt, long generation, Set<LockerMember> members) {
    public LockerMetadata {
        Objects.requireNonNull(lockerId, "lockerId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        Objects.requireNonNull(members, "members");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("a locker must have at least one member");
        }
        if (members.stream().anyMatch(member -> !lockerId.equals(member.lockerId()))) {
            throw new IllegalArgumentException("locker member belongs to a different locker");
        }
        members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    public LockerMetadata withMembers(Set<LockerMember> replacement) {
        return new LockerMetadata(lockerId, ownerUuid, password, createdAt, updatedAt, generation, replacement);
    }
}
