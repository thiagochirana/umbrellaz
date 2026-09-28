package dev.chirana.umbrellaz.lock;

import java.util.Arrays;
import java.util.Objects;

public record PasswordHash(byte[] salt, byte[] hash, String algorithm, int workFactor) {
    public PasswordHash {
        salt = copy(salt, PasswordKdf.SALT_LENGTH, "salt");
        hash = copy(hash, PasswordKdf.HASH_LENGTH, "hash");
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        if (!PasswordKdf.ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("Unsupported password algorithm: " + algorithm);
        }
        if (!PasswordKdf.isSupportedWorkFactor(workFactor)) {
            throw new IllegalArgumentException("Unsupported password work factor: " + workFactor);
        }
    }

    @Override
    public byte[] salt() {
        return salt.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PasswordHash that)) {
            return false;
        }
        return workFactor == that.workFactor && algorithm.equals(that.algorithm)
                && Arrays.equals(salt, that.salt) && Arrays.equals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(algorithm, workFactor);
        result = 31 * result + Arrays.hashCode(salt);
        return 31 * result + Arrays.hashCode(hash);
    }

    private static byte[] copy(byte[] value, int expectedLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(name + " must be exactly " + expectedLength + " bytes");
        }
        return value.clone();
    }
}
