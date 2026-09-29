package dev.chirana.umbrellaz.lock;

import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LockService implements AutoCloseable {
    private final PasswordPolicy passwordPolicy;
    private final PasswordKdf passwordKdf;
    private final LockPlacementPolicy placementPolicy;
    private final AttemptLimiter attempts;
    private final LockCache cache;
    private final Executor verificationExecutor;
    private final ExecutorService ownedExecutor;
    private final Set<PlayerLocker> unlocked = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<VerificationKey, CompletableFuture<VerificationOutcome>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LockPasswordProof, ProofBinding> pendingProofs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> playerEpochs = new ConcurrentHashMap<>();
    private final AtomicLong lifecycleEpoch = new AtomicLong();

    public LockService(PasswordPolicy passwordPolicy, PasswordKdf passwordKdf,
                       LockPlacementPolicy placementPolicy, AttemptLimiter attempts, LockCache cache) {
        this(passwordPolicy, passwordKdf, placementPolicy, attempts, cache, defaultExecutor(), true);
    }

    public LockService(PasswordPolicy passwordPolicy, PasswordKdf passwordKdf,
                       LockPlacementPolicy placementPolicy, AttemptLimiter attempts, LockCache cache,
                       Executor verificationExecutor) {
        this(passwordPolicy, passwordKdf, placementPolicy, attempts, cache, verificationExecutor, false);
    }

    private LockService(PasswordPolicy passwordPolicy, PasswordKdf passwordKdf,
                        LockPlacementPolicy placementPolicy, AttemptLimiter attempts, LockCache cache,
                        Executor verificationExecutor, boolean owned) {
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy");
        this.passwordKdf = Objects.requireNonNull(passwordKdf, "passwordKdf");
        this.placementPolicy = Objects.requireNonNull(placementPolicy, "placementPolicy");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.verificationExecutor = Objects.requireNonNull(verificationExecutor, "verificationExecutor");
        this.ownedExecutor = owned ? (ExecutorService) verificationExecutor : null;
    }

    public boolean isPlacementEligible(LockBlockType blockType) {
        return placementPolicy.isEligible(blockType);
    }

    public boolean isPlacementEligible(LockTarget intendedTarget, UUID installerUuid,
                                       LockBlockType expectedBlockType, PlacementProvenance provenance) {
        return placementPolicy.isEligible(intendedTarget, installerUuid, expectedBlockType, provenance);
    }

    public boolean canGroupDoubleChest(PlacementProvenance first, LockBlockType firstType,
                                       PlacementProvenance second, LockBlockType secondType) {
        return placementPolicy.canGroupDoubleChest(first, firstType, second, secondType);
    }

    LockCache cache() {
        return cache;
    }

    public CompletableFuture<PasswordHash> createPasswordAsync(String password) {
        String canonical = passwordPolicy.canonicalize(password);
        CompletableFuture<PasswordHash> result = new CompletableFuture<>();
        try {
            verificationExecutor.execute(() -> {
                try {
                    result.complete(passwordKdf.hash(canonical));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new PasswordCreationBusyException(exception));
        }
        return result;
    }

    PasswordHash createPasswordPrimitive(String password) {
        return passwordKdf.hash(passwordPolicy.canonicalize(password));
    }

    boolean verifyPasswordPrimitive(String password, PasswordHash stored) {
        return passwordKdf.verify(password, stored);
    }

    public LockAccessResult lookupAccess(UUID playerUuid, PlacementProvenance placement, LockAction action) {
        return lookupAccess(playerUuid, List.of(placement), action);
    }

    public LockAccessResult lookupAccess(UUID playerUuid, Collection<PlacementProvenance> placements,
                                         LockAction action) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(placements, "placements");
        Objects.requireNonNull(action, "action");
        if (placements.isEmpty()) {
            return unresolved(LockAccessDecision.NOT_READY, action);
        }
        LockerMetadata locker = null;
        for (PlacementProvenance placement : placements) {
            Objects.requireNonNull(placement, "placement member");
            LockCache.Lookup lookup = cache.lookup(placement);
            if (!lookup.ready()) {
                return unresolved(LockAccessDecision.NOT_READY, action);
            }
            if (lookup.locker() != null) {
                if (locker != null && !locker.lockerId().equals(lookup.locker().lockerId())) {
                    return unresolved(LockAccessDecision.NOT_READY, action);
                }
                locker = lookup.locker();
            }
        }
        if (locker == null) {
            return unresolved(LockAccessDecision.NOT_LOCKED, action);
        }
        PlayerLocker accessKey = new PlayerLocker(playerUuid, locker.lockerId());
        if (unlocked.contains(accessKey)) {
            return resolved(LockAccessDecision.GRANTED, action, locker, 0);
        }
        AttemptLimiter.AttemptResult attempt = attempts.status(playerUuid, locker.lockerId());
        if (attempt.state() == AttemptLimiter.ResultState.COOLDOWN) {
            return resolved(LockAccessDecision.COOLDOWN, action, locker, attempt.cooldownRemainingNanos());
        }
        return resolved(LockAccessDecision.LOCKED, action, locker, 0);
    }

    public CompletableFuture<PasswordVerification> verifyPasswordAsync(
            UUID playerUuid, Collection<PlacementProvenance> placements,
            LockAction action, String password) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(placements, "placements");
        Objects.requireNonNull(action, "action");
        List<PlacementProvenance> snapshot = immutablePlacements(placements);
        LockAccessResult lookup = lookupAccess(playerUuid, snapshot, action);
        if (lookup.decision() != LockAccessDecision.LOCKED) {
            return CompletableFuture.completedFuture(new PasswordVerification(lookup, null));
        }
        if (password == null) {
            return CompletableFuture.completedFuture(new PasswordVerification(
                    new LockAccessResult(LockAccessDecision.WRONG_PASSWORD, action,
                            lookup.lockerId(), lookup.ownerUuid(), 0), null));
        }
        UUID lockerId = lookup.lockerId();
        long playerEpoch = playerEpochs.getOrDefault(playerUuid, 0L);
        long currentLifecycleEpoch = lifecycleEpoch.get();
        LockerMetadata locker = lockerFor(snapshot, lockerId);
        if (locker == null) {
            return CompletableFuture.completedFuture(new PasswordVerification(
                    unresolved(LockAccessDecision.NOT_READY, action), null));
        }
        VerificationKey key = new VerificationKey(playerUuid, lockerId, currentLifecycleEpoch,
                playerEpoch, action, snapshot, passwordDiscriminator(password));
        final CompletableFuture<VerificationOutcome> verification;
        try {
            verification = getOrSubmitVerification(key, password, locker);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(new PasswordVerification(
                    new LockAccessResult(LockAccessDecision.VERIFICATION_BUSY, action,
                            lookup.lockerId(), lookup.ownerUuid(), 0), null));
        }
        return verification.thenApply(outcome -> {
            LockAccessResult result = new LockAccessResult(outcome.decision(), action,
                    lookup.lockerId(), lookup.ownerUuid(), outcome.cooldownRemainingNanos());
            if (outcome.decision() != LockAccessDecision.GRANTED) {
                return new PasswordVerification(result, null);
            }
            LockPasswordProof proof = new LockPasswordProof(UUID.randomUUID(), action);
            pendingProofs.put(proof, new ProofBinding(playerUuid, currentLifecycleEpoch,
                    playerEpoch, action, lockerId, lookup.ownerUuid(), snapshot));
            return new PasswordVerification(result, proof);
        });
    }

    public CompletableFuture<PasswordVerification> verifyPasswordAsync(
            UUID playerUuid, PlacementProvenance placement, LockAction action, String password) {
        return verifyPasswordAsync(playerUuid, List.of(placement), action, password);
    }

    public CompletableFuture<PasswordVerification> verifyPassword(
            UUID playerUuid, Collection<PlacementProvenance> placements,
            LockAction action, String password) {
        return verifyPasswordAsync(playerUuid, placements, action, password);
    }

    public CompletableFuture<PasswordVerification> verifyPassword(
            UUID playerUuid, PlacementProvenance placement, LockAction action, String password) {
        return verifyPasswordAsync(playerUuid, placement, action, password);
    }

    public synchronized LockAccessResult commitPasswordProof(LockPasswordProof proof) {
        Objects.requireNonNull(proof, "proof");
        ProofBinding binding = pendingProofs.remove(proof);
        if (binding == null) {
            return staleProofResult(proof.action(), null, null);
        }
        if (binding.lifecycleEpoch() != lifecycleEpoch.get()
                || binding.playerEpoch() != playerEpochs.getOrDefault(binding.playerUuid(), 0L)) {
            return staleProofResult(binding.action(), binding.lockerId(), binding.ownerUuid());
        }

        LockAccessResult current = lookupAccess(binding.playerUuid(), binding.placements(), binding.action());
        if (current.decision() != LockAccessDecision.LOCKED
                || !binding.lockerId().equals(current.lockerId())) {
            return staleProofResult(binding.action(), binding.lockerId(), binding.ownerUuid());
        }
        unlocked.add(new PlayerLocker(binding.playerUuid(), binding.lockerId()));
        return new LockAccessResult(LockAccessDecision.GRANTED, binding.action(),
                current.lockerId(), current.ownerUuid(), 0);
    }

    public synchronized LockAccessResult commitProof(LockPasswordProof proof) {
        return commitPasswordProof(proof);
    }

    public void discardPasswordProof(LockPasswordProof proof) {
        if (proof != null) {
            pendingProofs.remove(proof);
        }
    }

    public void discardProof(LockPasswordProof proof) {
        discardPasswordProof(proof);
    }

    public CompletableFuture<Void> loadCache(LockRepository repository, DatabaseExecutor databaseExecutor) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        cache.markNotReady();
        return databaseExecutor.submit(repository::findAllLockers)
                .thenAccept(lockers -> {
                    try {
                        cache.replaceSnapshot(lockers);
                    } catch (RuntimeException exception) {
                        cache.markNotReady();
                        throw exception;
                    }
                })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        cache.markNotReady();
                    }
                });
    }

    public CompletableFuture<Void> loadPlacementSnapshot(LockRepository repository, DatabaseExecutor databaseExecutor,
                                                          LockPlacementTracker placements) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        Objects.requireNonNull(placements, "placements");
        placements.markNotReady();
        return databaseExecutor.submit(repository::findAllPlacements)
                .thenAccept(placements::replace);
    }

    public CompletableFuture<Void> recordPlacementAsync(LockRepository repository, DatabaseExecutor databaseExecutor,
                                                         PlacementProvenance placement) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        Objects.requireNonNull(placement, "placement");
        return databaseExecutor.submit(() -> repository.recordPlacement(placement))
                .thenRun(() -> cache.removeLockerAtIfNotGeneration(placement.target(), placement.generationId()));
    }

    public CompletableFuture<Void> createLockerAsync(LockRepository repository, DatabaseExecutor databaseExecutor,
                                                      LockerMetadata locker) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        Objects.requireNonNull(locker, "locker");
        return databaseExecutor.submit(() -> repository.createLocker(locker))
                .thenRun(() -> cache.addLocker(locker));
    }

    public CompletableFuture<Boolean> invalidatePlacement(LockRepository repository, DatabaseExecutor databaseExecutor,
                                                           PlacementProvenance placement) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        Objects.requireNonNull(placement, "placement");
        LockCache.Lookup lookup = cache.lookup(placement);
        UUID affectedLockerId = lookup.locker() == null ? null : lookup.locker().lockerId();
        return databaseExecutor.submit(() -> repository.invalidatePlacementIfCurrent(
                        placement.target(), placement.generationId()))
                .thenApply(invalidated -> {
                    if (invalidated) {
                        cache.removeLockerAtGeneration(placement.target(), placement.generationId());
                        if (affectedLockerId != null) {
                            unlocked.removeIf(access -> access.lockerId().equals(affectedLockerId));
                        }
                    }
                    return invalidated;
                });
    }

    public CompletableFuture<Boolean> removeLockerAsync(LockRepository repository, DatabaseExecutor databaseExecutor,
                                                        UUID lockerId,
                                                        Collection<PlacementProvenance> expectedMembers) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        Objects.requireNonNull(lockerId, "lockerId");
        Objects.requireNonNull(expectedMembers, "expectedMembers");
        return databaseExecutor.submit(() -> repository.removeLockerIfMembers(lockerId, expectedMembers))
                .thenApply(removed -> {
                    if (removed) {
                        cache.removeLocker(lockerId);
                        unlocked.removeIf(access -> access.lockerId().equals(lockerId));
                    }
                    return removed;
                });
    }

    public synchronized void disconnect(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        playerEpochs.merge(playerUuid, 1L, Long::sum);
        unlocked.removeIf(access -> access.playerUuid().equals(playerUuid));
        pendingProofs.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(playerUuid));
    }

    public synchronized void resetOnRestart() {
        lifecycleEpoch.incrementAndGet();
        unlocked.clear();
        pendingProofs.clear();
        attempts.resetOnRestart();
        cache.markNotReady();
    }

    @Override
    public synchronized void close() {
        lifecycleEpoch.incrementAndGet();
        pendingProofs.clear();
        unlocked.clear();
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private CompletableFuture<VerificationOutcome> getOrSubmitVerification(VerificationKey key, String password,
                                                                              LockerMetadata locker) {
        CompletableFuture<VerificationOutcome> placeholder = new CompletableFuture<>();
        CompletableFuture<VerificationOutcome> existing = inFlight.putIfAbsent(key, placeholder);
        if (existing != null) {
            return existing;
        }
        try {
            verificationExecutor.execute(() -> {
                try {
                    placeholder.complete(verify(key, password, locker));
                } catch (Throwable failure) {
                    placeholder.completeExceptionally(failure);
                } finally {
                    inFlight.remove(key, placeholder);
                }
            });
        } catch (RejectedExecutionException exception) {
            inFlight.remove(key, placeholder);
            throw exception;
        }
        return placeholder;
    }

    private VerificationOutcome verify(VerificationKey key, String password, LockerMetadata locker) {
        boolean valid = passwordKdf.verify(password, locker.password());
        boolean current = key.lifecycleEpoch() == lifecycleEpoch.get()
                && key.playerEpoch() == playerEpochs.getOrDefault(key.playerUuid(), 0L);
        if (!current) {
            return new VerificationOutcome(LockAccessDecision.LOCKED, 0);
        }
        if (valid) {
            AttemptLimiter.AttemptResult success = attempts.recordSuccess(key.playerUuid(), key.lockerId());
            if (success.state() == AttemptLimiter.ResultState.CAPACITY) {
                return new VerificationOutcome(LockAccessDecision.VERIFICATION_BUSY, 0);
            }
            if (success.state() == AttemptLimiter.ResultState.COOLDOWN) {
                return new VerificationOutcome(LockAccessDecision.COOLDOWN,
                        success.cooldownRemainingNanos());
            }
            return new VerificationOutcome(LockAccessDecision.GRANTED, 0);
        }
        AttemptLimiter.AttemptResult result = attempts.recordFailure(key.playerUuid(), key.lockerId());
        LockAccessDecision decision = result.state() == AttemptLimiter.ResultState.COOLDOWN_STARTED
                || result.state() == AttemptLimiter.ResultState.COOLDOWN
                ? LockAccessDecision.COOLDOWN : LockAccessDecision.WRONG_PASSWORD;
        if (result.state() == AttemptLimiter.ResultState.CAPACITY) {
            decision = LockAccessDecision.VERIFICATION_BUSY;
        }
        return new VerificationOutcome(decision, result.cooldownRemainingNanos());
    }

    private LockAccessResult unresolved(LockAccessDecision decision, LockAction action) {
        return new LockAccessResult(decision, action, null, null, 0);
    }

    private LockAccessResult resolved(LockAccessDecision decision, LockAction action,
                                      LockerMetadata locker, long remaining) {
        return new LockAccessResult(decision, action, locker.lockerId(), locker.ownerUuid(), remaining);
    }

    private LockerMetadata lockerFor(List<PlacementProvenance> placements, UUID lockerId) {
        LockerMetadata locker = null;
        for (PlacementProvenance placement : placements) {
            LockCache.Lookup lookup = cache.lookup(placement);
            if (!lookup.ready()) {
                return null;
            }
            if (lookup.locker() != null) {
                if (locker != null && !locker.lockerId().equals(lookup.locker().lockerId())) {
                    return null;
                }
                locker = lookup.locker();
            }
        }
        return locker != null && locker.lockerId().equals(lockerId) ? locker : null;
    }

    private List<PlacementProvenance> immutablePlacements(Collection<PlacementProvenance> placements) {
        List<PlacementProvenance> snapshot = List.copyOf(placements);
        for (PlacementProvenance placement : snapshot) {
            Objects.requireNonNull(placement, "placement member");
        }
        return snapshot;
    }

    private String passwordDiscriminator(String password) {
        try {
            String canonical = passwordPolicy.canonicalize(password);
            return "valid:" + digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return "invalid:" + digest(password.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String digest(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LockAccessResult staleProofResult(LockAction action, UUID lockerId, UUID ownerUuid) {
        if (lockerId == null || ownerUuid == null) {
            return unresolved(LockAccessDecision.NOT_READY, action);
        }
        return new LockAccessResult(LockAccessDecision.LOCKED, action, lockerId, ownerUuid, 0);
    }

    private static ExecutorService defaultExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "umbrellaz-lock-kdf");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private record PlayerLocker(UUID playerUuid, UUID lockerId) {
    }

    private record VerificationKey(UUID playerUuid, UUID lockerId, long lifecycleEpoch, long playerEpoch,
                                   LockAction action, List<PlacementProvenance> placements,
                                   String passwordDiscriminator) {
        private VerificationKey {
            placements = List.copyOf(placements);
            Objects.requireNonNull(passwordDiscriminator, "passwordDiscriminator");
        }
    }

    private record VerificationOutcome(LockAccessDecision decision, long cooldownRemainingNanos) {
    }

    public record PasswordVerification(LockAccessResult result, LockPasswordProof proof) {
        public PasswordVerification {
            Objects.requireNonNull(result, "result");
            if (proof != null && result.decision() != LockAccessDecision.GRANTED) {
                throw new IllegalArgumentException("only successful verification may carry a proof");
            }
        }

        public boolean hasProof() {
            return proof != null;
        }
    }

    public record LockPasswordProof(UUID token, LockAction action) {
        public LockPasswordProof {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(action, "action");
        }
    }

    private record ProofBinding(UUID playerUuid, long lifecycleEpoch, long playerEpoch,
                                LockAction action, UUID lockerId, UUID ownerUuid,
                                List<PlacementProvenance> placements) {
        private ProofBinding {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(lockerId, "lockerId");
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            placements = List.copyOf(placements);
        }
    }
}
