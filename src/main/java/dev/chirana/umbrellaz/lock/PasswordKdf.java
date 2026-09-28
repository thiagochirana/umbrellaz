package dev.chirana.umbrellaz.lock;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordKdf {
    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int DEFAULT_WORK_FACTOR = 120_000;
    public static final int DEFAULT_SALT_LENGTH = 16;
    public static final int SALT_LENGTH = 16;
    public static final int HASH_LENGTH = 32;
    public static final int MIN_WORK_FACTOR = 10_000;
    public static final int MAX_WORK_FACTOR = 1_000_000;

    private final PasswordPolicy policy;
    private final SecureRandom random;
    private final int saltLength;
    private final int workFactor;

    public PasswordKdf() {
        this(new PasswordPolicy(), new SecureRandom(), DEFAULT_SALT_LENGTH, DEFAULT_WORK_FACTOR);
    }

    public PasswordKdf(PasswordPolicy policy, SecureRandom random, int saltLength, int workFactor) {
        if (saltLength != SALT_LENGTH) {
            throw new IllegalArgumentException("saltLength must be exactly " + SALT_LENGTH + " bytes");
        }
        if (!isSupportedWorkFactor(workFactor)) {
            throw new IllegalArgumentException("Unsupported password work factor: " + workFactor);
        }
        this.policy = policy;
        this.random = random;
        this.saltLength = saltLength;
        this.workFactor = workFactor;
    }

    public PasswordHash hash(String password) {
        String canonical = policy.canonicalize(password);
        byte[] salt = new byte[saltLength];
        random.nextBytes(salt);
        return new PasswordHash(salt, derive(canonical, salt, workFactor), ALGORITHM, workFactor);
    }

    public boolean verify(String password, PasswordHash stored) {
        if (password == null || stored == null || !isSupported(stored)) {
            return false;
        }
        final String canonical;
        try {
            canonical = policy.canonicalize(password);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        try {
            byte[] candidate = derive(canonical, stored.salt(), stored.workFactor());
            return MessageDigest.isEqual(candidate, stored.hash());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static boolean isSupportedWorkFactor(int workFactor) {
        return workFactor >= MIN_WORK_FACTOR && workFactor <= MAX_WORK_FACTOR;
    }

    public static boolean isSupported(PasswordHash stored) {
        return stored != null && ALGORITHM.equals(stored.algorithm())
                && stored.salt().length == SALT_LENGTH && stored.hash().length == HASH_LENGTH
                && isSupportedWorkFactor(stored.workFactor());
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        PBEKeySpec specification = new PBEKeySpec(characters, salt, iterations, HASH_LENGTH * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password KDF is unavailable", exception);
        } finally {
            Arrays.fill(characters, '\0');
            specification.clearPassword();
        }
    }
}
