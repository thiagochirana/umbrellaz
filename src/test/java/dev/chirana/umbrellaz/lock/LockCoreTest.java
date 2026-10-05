package dev.chirana.umbrellaz.lock;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletionException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockCoreTest {
    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    @Test
    void acceptsOnlyAsciiLetterAndThreeAsciiDigitsAndNormalizesCase() {
        assertTrue(passwordPolicy.isValid("a123"));
        assertEquals("A123", passwordPolicy.canonicalize("a123"));
        assertFalse(passwordPolicy.isValid("ab23"));
        assertFalse(passwordPolicy.isValid("A12٣"));
        assertFalse(passwordPolicy.isValid("A1234"));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicy.canonicalize("1234"));
    }

    @Test
    void hashesAndVerifiesWithoutAcceptingWrongPassword() {
        PasswordKdf kdf = new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000);
        PasswordHash hash = kdf.hash("a123");

        assertTrue(kdf.verify("A123", hash));
        assertFalse(kdf.verify("B123", hash));
        assertFalse(kdf.verify("not-a-password", hash));
        assertEquals(PasswordKdf.ALGORITHM, hash.algorithm());
        assertEquals(10_000, hash.workFactor());
    }

    @Test
    void rejectsUnsupportedPersistedPasswordParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordHash(new byte[15], new byte[32], PasswordKdf.ALGORITHM, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordHash(new byte[16], new byte[31], PasswordKdf.ALGORITHM, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordHash(new byte[16], new byte[32], "MD5", 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordHash(new byte[16], new byte[32], PasswordKdf.ALGORITHM, 9_999));
    }

    @Test
    void limitsThreeConsecutiveFailuresAndResetsAfterCooldownOrSuccess() {
        AtomicLong now = new AtomicLong();
        AttemptLimiter limiter = new AttemptLimiter(now::get);
        UUID player = UUID.randomUUID();
        UUID locker = UUID.randomUUID();

        assertEquals(AttemptLimiter.ResultState.FAILURE, limiter.recordFailure(player, locker).state());
        assertEquals(AttemptLimiter.ResultState.FAILURE, limiter.recordFailure(player, locker).state());
        assertEquals(AttemptLimiter.ResultState.COOLDOWN_STARTED, limiter.recordFailure(player, locker).state());
        assertTrue(limiter.isCoolingDown(player, locker));
        now.addAndGet(AttemptLimiter.COOLDOWN_NANOS);
        assertEquals(AttemptLimiter.ResultState.FAILURE, limiter.recordFailure(player, locker).state());
        limiter.recordSuccess(player, locker);
        assertEquals(0, limiter.status(player, locker).consecutiveFailures());

        limiter.recordFailure(player, locker);
        limiter.resetOnRestart();
        assertFalse(limiter.isCoolingDown(player, locker));
    }

    @Test
    void cleansStalePartialAttemptsAndUsesMonotonicElapsedTime() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 10);
        AttemptLimiter limiter = new AttemptLimiter(now::get);
        UUID player = UUID.randomUUID();
        UUID locker = UUID.randomUUID();
        limiter.recordFailure(player, locker);
        assertEquals(1, limiter.trackedStateCount());
        now.addAndGet(AttemptLimiter.COOLDOWN_NANOS);
        assertEquals(0, limiter.trackedStateCount());
    }

    @Test
    void capacityDoesNotEvictActiveCooldownsOrCountRejectedAttempts() {
        AtomicLong now = new AtomicLong();
        AttemptLimiter limiter = new AttemptLimiter(now::get, 2);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID thirdPlayer = UUID.randomUUID();
        UUID firstLocker = UUID.randomUUID();
        UUID secondLocker = UUID.randomUUID();
        UUID thirdLocker = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            limiter.recordFailure(firstPlayer, firstLocker);
            limiter.recordFailure(secondPlayer, secondLocker);
        }

        assertEquals(AttemptLimiter.ResultState.CAPACITY,
                limiter.recordFailure(thirdPlayer, thirdLocker).state());
        assertTrue(limiter.isCoolingDown(firstPlayer, firstLocker));
        assertTrue(limiter.isCoolingDown(secondPlayer, secondLocker));
        now.addAndGet(AttemptLimiter.COOLDOWN_NANOS);
        assertEquals(AttemptLimiter.ResultState.FAILURE,
                limiter.recordFailure(thirdPlayer, thirdLocker).state());
    }

    @Test
    void capacityDoesNotEvictPartialFailureStates() {
        AttemptLimiter limiter = new AttemptLimiter(() -> 0, 2);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID thirdPlayer = UUID.randomUUID();
        UUID firstLocker = UUID.randomUUID();
        UUID secondLocker = UUID.randomUUID();
        UUID thirdLocker = UUID.randomUUID();

        limiter.recordFailure(firstPlayer, firstLocker);
        limiter.recordFailure(secondPlayer, secondLocker);
        limiter.recordFailure(secondPlayer, secondLocker);

        assertEquals(AttemptLimiter.ResultState.CAPACITY,
                limiter.recordFailure(thirdPlayer, thirdLocker).state());
        assertEquals(1, limiter.status(firstPlayer, firstLocker).consecutiveFailures());
        assertEquals(2, limiter.status(secondPlayer, secondLocker).consecutiveFailures());
    }

    @Test
    void keepsWorldDimensionAndPositionInTargetIdentity() {
        LockTarget overworld = target("world", "overworld", 1, 2, 3);
        LockTarget nether = target("world", "nether", 1, 2, 3);
        LockTarget otherWorld = target("other", "overworld", 1, 2, 3);

        assertFalse(overworld.equals(nether));
        assertFalse(overworld.equals(otherWorld));
    }

    @Test
    void groupsOnlyAdjacentSameOwnerChestsInSameWorldAndDimension() {
        UUID owner = UUID.randomUUID();
        PlacementProvenance first = placement("world", "overworld", 1, 2, 3, owner, LockBlockType.CHEST);
        PlacementProvenance second = placement("world", "overworld", 2, 2, 3, owner, LockBlockType.CHEST);
        PlacementProvenance vertical = placement("world", "overworld", 1, 3, 3, owner, LockBlockType.CHEST);
        PlacementProvenance otherOwner = placement("world", "overworld", 2, 2, 3, UUID.randomUUID(), LockBlockType.CHEST);
        LockPlacementPolicy policy = new LockPlacementPolicy();

        assertTrue(policy.canGroupDoubleChest(first, LockBlockType.CHEST, second, LockBlockType.CHEST));
        assertFalse(policy.canGroupDoubleChest(first, LockBlockType.CHEST, otherOwner, LockBlockType.CHEST));
        assertFalse(policy.canGroupDoubleChest(first, LockBlockType.TRAPPED_CHEST, second, LockBlockType.CHEST));
        assertFalse(policy.canGroupDoubleChest(first, LockBlockType.CHEST, second, LockBlockType.BARREL));
        assertFalse(policy.canGroupDoubleChest(first, LockBlockType.CHEST, vertical, LockBlockType.CHEST));
        assertFalse(policy.canGroupDoubleChest(first, LockBlockType.CHEST,
                placement("world", "nether", 2, 2, 3, owner, LockBlockType.CHEST), LockBlockType.CHEST));
        assertFalse(policy.canGroupDoubleChest(null, LockBlockType.CHEST, second, LockBlockType.CHEST));
    }

    @Test
    void placementEligibilityRejectsTargetInstallerAndTypeMismatches() {
        UUID installer = UUID.randomUUID();
        LockTarget intended = target("world", "overworld", 1, 2, 3);
        PlacementProvenance provenance = new PlacementProvenance(intended, UUID.randomUUID(), installer,
                LockBlockType.BARREL, Instant.now());
        LockPlacementPolicy policy = new LockPlacementPolicy();

        assertTrue(policy.isEligible(intended, installer, LockBlockType.BARREL, provenance));
        assertFalse(policy.isEligible(target("world", "overworld", 2, 2, 3), installer,
                LockBlockType.BARREL, provenance));
        assertFalse(policy.isEligible(intended, UUID.randomUUID(), LockBlockType.BARREL, provenance));
        assertFalse(policy.isEligible(intended, installer, LockBlockType.CHEST, provenance));
        assertFalse(policy.isEligible(intended, installer, LockBlockType.BARREL, null));
    }

    @Test
    void cacheFailsClosedUntilACompleteSnapshotIsLoaded() {
        LockCache cache = new LockCache();
        LockTarget target = target("world", "overworld", 1, 2, 3);
        LockerMetadata cachedLocker = locker(target);
        LockerMember cachedMember = cachedLocker.members().iterator().next();
        PlacementProvenance placement = new PlacementProvenance(target, cachedMember.generationId(),
                cachedMember.placedBy(), cachedMember.blockType(), Instant.now());

        assertFalse(cache.isReady());
        assertFalse(cache.resolve(placement).isPresent());
        assertFalse(cache.lookup(placement).ready());
        cache.replaceSnapshot(List.of(cachedLocker));
        assertTrue(cache.isReady());
        assertTrue(cache.resolve(placement).isPresent());
        assertTrue(cache.resolve(new PlacementProvenance(target, UUID.randomUUID(), cachedMember.placedBy(),
                cachedMember.blockType(), Instant.now())).isEmpty());
        assertTrue(cache.resolve(new PlacementProvenance(target("world", "nether", 1, 2, 3),
                cachedMember.generationId(), cachedMember.placedBy(), cachedMember.blockType(), Instant.now())).isEmpty());
        assertTrue(cache.resolve(new PlacementProvenance(target("other", "overworld", 1, 2, 3),
                cachedMember.generationId(), cachedMember.placedBy(), cachedMember.blockType(), Instant.now())).isEmpty());
        cache.markNotReady();
        assertFalse(cache.lookup(placement).ready());
    }

    @Test
    void serviceGrantsAllActionsUntilDisconnectOnlyAfterCorrectPassword() {
        PasswordKdf kdf = new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000);
        LockCache cache = new LockCache();
        LockTarget target = target("world", "overworld", 1, 2, 3);
        UUID lockerId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        PlacementProvenance placement = new PlacementProvenance(target, generation, owner, LockBlockType.CHEST, Instant.now());
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        LockerMetadata locker = new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, target, generation, owner, LockBlockType.CHEST)));
        cache.replaceSnapshot(List.of(locker));
        LockService service = new LockService(passwordPolicy, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache);
        UUID player = UUID.randomUUID();

        assertEquals(LockAccessDecision.LOCKED,
                service.lookupAccess(player, placement, LockAction.OPEN).decision());
        assertEquals(LockAccessDecision.WRONG_PASSWORD,
                verifyAndCommitPassword(service, player, List.of(placement), LockAction.OPEN, null).decision());
        assertEquals(LockAccessDecision.WRONG_PASSWORD,
                verifyAndCommitPassword(service, player, List.of(placement), LockAction.OPEN, "B123").decision());
        assertEquals(LockAccessDecision.GRANTED,
                verifyAndCommitPassword(service, player, List.of(placement), LockAction.OPEN, "a123").decision());
        assertEquals(LockAccessDecision.GRANTED,
                service.lookupAccess(player, placement, LockAction.BREAK).decision());
        service.disconnect(player);
        assertEquals(LockAccessDecision.WRONG_PASSWORD,
                verifyAndCommitPassword(service, player, List.of(placement), LockAction.REMOVE, "B123").decision());
        service.close();
    }

    @Test
    void multiMemberLookupFindsTheLockerThroughEitherLogicalMember() {
        PasswordKdf kdf = new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000);
        LockTarget first = target("world", "overworld", 10, 2, 3);
        LockTarget second = target("world", "overworld", 11, 2, 3);
        UUID owner = UUID.randomUUID();
        UUID lockerId = UUID.randomUUID();
        UUID firstGeneration = UUID.randomUUID();
        UUID secondGeneration = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        LockerMetadata locker = new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, first, firstGeneration, owner, LockBlockType.CHEST),
                        new LockerMember(lockerId, second, secondGeneration, owner, LockBlockType.CHEST)));
        LockCache cache = new LockCache();
        cache.replaceSnapshot(List.of(locker));
        PlacementProvenance firstPlacement = new PlacementProvenance(first, firstGeneration, owner,
                LockBlockType.CHEST, now);
        PlacementProvenance secondPlacement = new PlacementProvenance(second, secondGeneration, owner,
                LockBlockType.CHEST, now);
        LockService service = new LockService(passwordPolicy, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache, Runnable::run);

        assertEquals(LockAccessDecision.LOCKED,
                service.lookupAccess(UUID.randomUUID(), List.of(secondPlacement, firstPlacement), LockAction.OPEN).decision());
        assertEquals(LockAccessDecision.GRANTED,
                verifyAndCommitPassword(service, UUID.randomUUID(), List.of(firstPlacement, secondPlacement),
                        LockAction.OPEN, "A123").decision());
        service.close();
    }

    @Test
    void placementSnapshotFailsClosedBeforeLoadAndRetainsUnlockedProvenanceAfterLoad() {
        LockPlacementTracker tracker = new LockPlacementTracker();
        LockTarget target = target("world-instance", "overworld", 1, 2, 3);
        PlacementProvenance placement = placement("world-instance", "overworld", 1, 2, 3,
                UUID.randomUUID(), LockBlockType.BARREL);

        tracker.markNotReady();
        assertTrue(tracker.find(target).isEmpty());
        tracker.replace(List.of(placement));
        assertEquals(placement, tracker.find(target).orElseThrow());
    }

    @Test
    void pendingPlacementRemainsVisibleToMutationLookupButNotNormalAccessLookup() {
        LockPlacementTracker tracker = new LockPlacementTracker();
        PlacementProvenance placement = placement("world-instance", "overworld", 4, 2, 3,
                UUID.randomUUID(), LockBlockType.BARREL);

        tracker.replace(List.of(placement));
        tracker.markPending(placement);

        assertTrue(tracker.find(placement.target()).isEmpty());
        assertEquals(placement, tracker.findForMutation(placement.target()).orElseThrow());
        tracker.markPersisted(placement);
        assertEquals(placement, tracker.find(placement.target()).orElseThrow());
    }

    @Test
    void verificationSaturationIsExplicitAndDoesNotConsumeAnAttempt() {
        PasswordKdf kdf = new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000);
        LockTarget target = target("world", "overworld", 1, 2, 3);
        UUID lockerId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        PlacementProvenance placement = new PlacementProvenance(target, generation, owner, LockBlockType.CHEST, Instant.now());
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        LockCache cache = new LockCache();
        cache.replaceSnapshot(List.of(new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, target, generation, owner, LockBlockType.CHEST)))));
        LockService service = new LockService(passwordPolicy, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache, command -> {
                    throw new RejectedExecutionException("test saturation");
                });
        UUID player = UUID.randomUUID();

        assertEquals(LockAccessDecision.VERIFICATION_BUSY,
                verifyAndCommitPassword(service, player, List.of(placement), LockAction.OPEN, "A123").decision());
        assertEquals(LockAccessDecision.LOCKED,
                service.lookupAccess(player, placement, LockAction.OPEN).decision());
        service.close();
    }

    @Test
    void asyncPasswordCreationUsesExecutorAndVerificationPlaceholderCanBeReused() {
        PasswordKdf kdf = new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000);
        LockService service = new LockService(passwordPolicy, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), new LockCache(), Runnable::run);
        PasswordHash created = service.createPasswordAsync("a123").join();
        assertTrue(kdf.verify("A123", created));

        LockTarget target = target("world", "overworld", 5, 2, 3);
        UUID lockerId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        PlacementProvenance placement = new PlacementProvenance(target, generation, owner, LockBlockType.CHEST, Instant.now());
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        LockCache cache = new LockCache();
        cache.replaceSnapshot(List.of(new LockerMetadata(lockerId, owner, created, now, now, 1,
                Set.of(new LockerMember(lockerId, target, generation, owner, LockBlockType.CHEST)))));
        LockService verificationService = new LockService(passwordPolicy, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache, Runnable::run);
        UUID player = UUID.randomUUID();
        assertEquals(LockAccessDecision.GRANTED,
                verifyAndCommitPassword(verificationService, player, List.of(placement), LockAction.OPEN, "A123").decision());
        verificationService.disconnect(player);
        assertEquals(LockAccessDecision.GRANTED,
                verifyAndCommitPassword(verificationService, player, List.of(placement), LockAction.OPEN, "A123").decision());
        service.close();
        verificationService.close();
    }

    @Test
    void passwordCreationSaturationIsAnExplicitFailedFuture() {
        LockService service = new LockService(passwordPolicy,
                new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000),
                new LockPlacementPolicy(), new AttemptLimiter(), new LockCache(), command -> {
                    throw new RejectedExecutionException("test saturation");
                });
        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.createPasswordAsync("A123").join());
        assertTrue(failure.getCause() instanceof PasswordCreationBusyException);
        service.close();
    }

    @Test
    void lockItemMarkerRequiresCurrentVersion() {
        CompoundTag current = new CompoundTag();
        current.putBoolean("umbrellaz:lock_item", true);
        current.putInt("umbrellaz:lock_item_version", 1);
        assertTrue(LockItem.isCurrentMarker(CustomData.of(current)));
        assertFalse(LockItem.isCurrentMarker(null));

        CompoundTag altered = new CompoundTag();
        altered.putBoolean("umbrellaz:lock_item", true);
        altered.putInt("umbrellaz:lock_item_version", 2);
        assertFalse(LockItem.isCurrentMarker(CustomData.of(altered)));
    }

    @Test
    void lockItemCarriesTheModernCustomModelDataSelector() {
        CustomModelData modelData = LockItem.modelData();
        assertEquals(1.0F, modelData.getFloat(0));
    }

    @Test
    void lockEventsUsesTheVanillaInteractionMargin() {
        assertEquals(1.0D, LockEvents.VANILLA_BLOCK_INTERACTION_MARGIN);
    }

    @Test
    void blockItemsPassThroughLockedStorageTargetsToVanillaPlacement() {
        assertTrue(LockEvents.shouldPassLockedTargetToVanilla(true, LockAccessDecision.LOCKED));
        assertFalse(LockEvents.shouldPassLockedTargetToVanilla(false, LockAccessDecision.LOCKED));
        assertFalse(LockEvents.shouldPassLockedTargetToVanilla(true, LockAccessDecision.NOT_LOCKED));
    }

    @Test
    void separateSingleChestNextToLockedChestIsAllowed() {
        BlockPos locked = new BlockPos(0, 2, 0);
        BlockPos candidate = new BlockPos(1, 2, 0);

        assertTrue(LockEvents.allowsCachedPlacement(candidate, candidate, locked::equals));
    }

    @Test
    void chestMergeIntoLockedTopologyRemainsRejected() {
        BlockPos locked = new BlockPos(0, 2, 0);
        BlockPos candidate = new BlockPos(1, 2, 0);

        assertFalse(LockEvents.allowsCachedPlacement(candidate, locked, locked::equals));
        assertEquals("Destranque o baú para permitir expandi-lo.", LockEvents.LOCKED_CHEST_MERGE_MESSAGE);
    }

    @Test
    void ordinaryNonStorageBlocksAreNotLockPlacementTargets() {
        assertTrue(LockEvents.isUnprotectedPlacement(Optional.empty()));
    }

    @Test
    void breakTransactionKeepsInvalidationAfterPhysicalDestruction() {
        BreakTransaction transaction = new BreakTransaction();

        assertFalse(transaction.markInvalidation(true));
        assertTrue(transaction.isPending());
        assertFalse(transaction.markPhysicalDestruction(false));
        assertFalse(transaction.isPending());
        assertFalse(transaction.markInvalidation(true));

        BreakTransaction successful = new BreakTransaction();
        assertTrue(successful.markPhysicalDestruction(true));
        assertTrue(successful.isPending());
        assertFalse(successful.markInvalidation(false));
        assertTrue(successful.isPending());
        assertTrue(successful.markInvalidation(true));
        assertFalse(successful.isPending());
    }

    @Test
    void environmentalProtectionFailsClosedOnlyForSupportedPositions() {
        assertFalse(LockProtection.Decision.NOT_SUPPORTED.blocksEnvironmentalMutation());
        assertFalse(LockProtection.Decision.UNLOCKED.blocksEnvironmentalMutation());
        assertTrue(LockProtection.Decision.NOT_READY.blocksEnvironmentalMutation());
        assertTrue(LockProtection.Decision.LOCKED.blocksEnvironmentalMutation());
    }

    @Test
    void markerIdentityRequiresTheNamespacedMarkerAndValidLockerUuid() {
        UUID locker = UUID.randomUUID();
        Set<String> tags = Set.of(LockMarkerIdentity.MARKER_TAG, LockMarkerIdentity.lockerTag(locker));
        assertTrue(LockMarkerIdentity.isMarker(tags));
        assertEquals(locker, LockMarkerIdentity.lockerId(tags).orElseThrow());
        assertFalse(LockMarkerIdentity.isMarker(Set.of(LockMarkerIdentity.MARKER_TAG)));
        assertFalse(LockMarkerIdentity.isMarker(Set.of(LockMarkerIdentity.MARKER_TAG,
                "umbrellaz:locker=not-a-uuid")));
    }

    @Test
    void creationCompletionCommitsOnlyAfterPersistenceAndFreshValidation() {
        assertEquals(CreationCompletionDecision.Outcome.COMMIT,
                CreationCompletionDecision.decide(true, true));
        assertEquals(CreationCompletionDecision.Outcome.COMPENSATE,
                CreationCompletionDecision.decide(true, false));
        assertEquals(CreationCompletionDecision.Outcome.COMPENSATE,
                CreationCompletionDecision.decide(false, false));
    }

    private LockAccessResult verifyAndCommitPassword(LockService service, UUID playerUuid,
                                                     List<PlacementProvenance> placements,
                                                     LockAction action, String password) {
        LockService.PasswordVerification verification = service.verifyPasswordAsync(
                playerUuid, placements, action, password).join();
        return verification.hasProof()
                ? service.commitPasswordProof(verification.proof())
                : verification.result();
    }

    private LockerMetadata locker(LockTarget target) {
        PasswordKdf kdf = new PasswordKdf(passwordPolicy, new SecureRandom(), 16, 10_000);
        UUID lockerId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        return new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, target, UUID.randomUUID(), owner, LockBlockType.CHEST)));
    }

    private LockTarget target(String world, String dimension, int x, int y, int z) {
        return new LockTarget(new WorldIdentity(world), new DimensionIdentity(dimension), new BlockPosition(x, y, z));
    }

    private PlacementProvenance placement(String world, String dimension, int x, int y, int z,
                                          UUID placedBy, LockBlockType blockType) {
        return new PlacementProvenance(target(world, dimension, x, y, z), UUID.randomUUID(), placedBy,
                blockType, Instant.now());
    }
}
