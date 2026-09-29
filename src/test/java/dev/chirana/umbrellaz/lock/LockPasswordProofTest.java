package dev.chirana.umbrellaz.lock;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockPasswordProofTest {
    private static final PasswordPolicy POLICY = new PasswordPolicy();

    @Test
    void verificationDoesNotGrantUntilAProofIsCommittedAndCommitIsOneShot() {
        Fixture fixture = fixture();
        UUID player = UUID.randomUUID();

        LockService.PasswordVerification verification = fixture.service()
                .verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "A123").join();
        assertEquals(LockAccessDecision.GRANTED, verification.result().decision());
        assertNotNull(verification.proof());
        assertEquals(LockAccessDecision.LOCKED,
                fixture.service().lookupAccess(player, fixture.placements(), LockAction.OPEN).decision());

        assertEquals(LockAccessDecision.GRANTED,
                fixture.service().commitPasswordProof(verification.proof()).decision());
        assertEquals(LockAccessDecision.GRANTED,
                fixture.service().lookupAccess(player, fixture.placements(), LockAction.BREAK).decision());
        assertNotEquals(LockAccessDecision.GRANTED,
                fixture.service().commitPasswordProof(verification.proof()).decision());
        fixture.service().close();
    }

    @Test
    void wrongPasswordCooldownAndBusyResultsNeverCarryProof() {
        Fixture fixture = fixture();
        UUID player = UUID.randomUUID();

        assertNoProof(fixture.service().verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "B123").join(),
                LockAccessDecision.WRONG_PASSWORD);
        assertNoProof(fixture.service().verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "B123").join(),
                LockAccessDecision.WRONG_PASSWORD);
        assertNoProof(fixture.service().verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "B123").join(),
                LockAccessDecision.COOLDOWN);
        assertNoProof(fixture.service().verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "A123").join(),
                LockAccessDecision.COOLDOWN);
        fixture.service().close();

        LockService busy = new LockService(POLICY, fixture.kdf(), new LockPlacementPolicy(),
                new AttemptLimiter(), fixture.cache(), ignored -> {
                    throw new RejectedExecutionException("busy");
                });
        assertNoProof(busy.verifyPasswordAsync(UUID.randomUUID(), fixture.placements(), LockAction.OPEN, "A123").join(),
                LockAccessDecision.VERIFICATION_BUSY);
        busy.close();
    }

    @Test
    void disconnectAndDiscardInvalidateProofs() {
        Fixture fixture = fixture();
        UUID player = UUID.randomUUID();
        LockService.PasswordVerification disconnected = fixture.service()
                .verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "A123").join();
        fixture.service().disconnect(player);
        assertNotEquals(LockAccessDecision.GRANTED,
                fixture.service().commitPasswordProof(disconnected.proof()).decision());

        LockService.PasswordVerification discarded = fixture.service()
                .verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "A123").join();
        fixture.service().discardPasswordProof(discarded.proof());
        assertNotEquals(LockAccessDecision.GRANTED,
                fixture.service().commitPasswordProof(discarded.proof()).decision());
        fixture.service().close();
    }

    @Test
    void lifecycleResetAndExactPlacementSnapshotPreventCommit() {
        Fixture fixture = fixture();
        UUID player = UUID.randomUUID();
        LockService.PasswordVerification reset = fixture.service()
                .verifyPasswordAsync(player, fixture.placements(), LockAction.OPEN, "A123").join();
        fixture.service().resetOnRestart();
        assertNotEquals(LockAccessDecision.GRANTED,
                fixture.service().commitPasswordProof(reset.proof()).decision());
        fixture.service().close();

        Fixture changed = fixture();
        LockService.PasswordVerification proof = changed.service()
                .verifyPasswordAsync(player, changed.placements(), LockAction.OPEN, "A123").join();
        PlacementProvenance replacement = placement(changed.target(), UUID.randomUUID(), changed.owner());
        LockerMetadata replaced = new LockerMetadata(changed.lockerId(), changed.owner(),
                changed.kdf().hash("A123"), changed.now(), changed.now(), 1,
                Set.of(new LockerMember(changed.lockerId(), changed.target(), replacement.generationId(),
                        changed.owner(), LockBlockType.CHEST)));
        changed.cache().replaceSnapshot(List.of(replaced));
        assertNotEquals(LockAccessDecision.GRANTED,
                changed.service().commitPasswordProof(proof.proof()).decision());

        LockService.LockPasswordProof actionMismatch = new LockService.LockPasswordProof(
                proof.proof().token(), LockAction.BREAK);
        assertNotEquals(LockAccessDecision.GRANTED,
                changed.service().commitPasswordProof(actionMismatch).decision());
        changed.service().close();
    }

    @Test
    void successfulProofRetainsOrderedPlacementBinding() {
        PasswordKdf kdf = new PasswordKdf(POLICY, new SecureRandom(), 16, 10_000);
        LockCache cache = new LockCache();
        LockTarget first = target(1);
        LockTarget second = target(2);
        UUID owner = UUID.randomUUID();
        UUID lockerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        PlacementProvenance firstPlacement = placement(first, UUID.randomUUID(), owner);
        PlacementProvenance secondPlacement = placement(second, UUID.randomUUID(), owner);
        cache.replaceSnapshot(List.of(new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, first, firstPlacement.generationId(), owner, LockBlockType.CHEST),
                        new LockerMember(lockerId, second, secondPlacement.generationId(), owner, LockBlockType.CHEST)))));
        LockService service = new LockService(POLICY, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache, Runnable::run);
        LockService.PasswordVerification verification = service.verifyPasswordAsync(UUID.randomUUID(),
                List.of(secondPlacement, firstPlacement), LockAction.REMOVE, "A123").join();

        assertTrue(verification.hasProof());
        assertEquals(LockAccessDecision.GRANTED, service.commitPasswordProof(verification.proof()).decision());
        service.close();
    }

    @Test
    void overlappingDifferentPasswordsCannotShareASuccessfulVerification() throws Exception {
        PasswordKdf kdf = new PasswordKdf(POLICY, new SecureRandom(), 16, 10_000);
        LockCache cache = new LockCache();
        LockTarget target = target(1);
        UUID owner = UUID.randomUUID();
        UUID lockerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        PlacementProvenance placement = placement(target, UUID.randomUUID(), owner);
        cache.replaceSnapshot(List.of(new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, target, placement.generationId(), owner, LockBlockType.CHEST)))));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Executor verificationExecutor = command -> workers.execute(() -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("verification did not become releasable");
                }
                command.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        LockService service = new LockService(POLICY, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache, verificationExecutor);
        UUID player = UUID.randomUUID();

        try {
            CompletableFuture<LockService.PasswordVerification> correct =
                    service.verifyPasswordAsync(player, List.of(placement), LockAction.OPEN, "a123");
            CompletableFuture<LockService.PasswordVerification> wrong =
                    service.verifyPasswordAsync(player, List.of(placement), LockAction.OPEN, "B123");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();

            LockService.PasswordVerification correctResult = correct.join();
            LockService.PasswordVerification wrongResult = wrong.join();
            assertEquals(LockAccessDecision.GRANTED, correctResult.result().decision());
            assertTrue(correctResult.hasProof());
            assertEquals(LockAccessDecision.WRONG_PASSWORD, wrongResult.result().decision());
            assertNull(wrongResult.proof());
        } finally {
            release.countDown();
            service.close();
            workers.shutdownNow();
        }
    }

    private static void assertNoProof(LockService.PasswordVerification verification, LockAccessDecision decision) {
        assertEquals(decision, verification.result().decision());
        assertNull(verification.proof());
    }

    private static Fixture fixture() {
        PasswordKdf kdf = new PasswordKdf(POLICY, new SecureRandom(), 16, 10_000);
        LockCache cache = new LockCache();
        LockTarget target = target(1);
        UUID owner = UUID.randomUUID();
        UUID lockerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        PlacementProvenance placement = placement(target, UUID.randomUUID(), owner);
        cache.replaceSnapshot(List.of(new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(lockerId, target, placement.generationId(), owner, LockBlockType.CHEST)))));
        LockService service = new LockService(POLICY, kdf, new LockPlacementPolicy(),
                new AttemptLimiter(), cache, Runnable::run);
        return new Fixture(service, kdf, cache, target, placement, lockerId, owner, now);
    }

    private static PlacementProvenance placement(LockTarget target, UUID generation, UUID owner) {
        return new PlacementProvenance(target, generation, owner, LockBlockType.CHEST, Instant.now());
    }

    private static LockTarget target(int x) {
        return new LockTarget(new WorldIdentity("world"), new DimensionIdentity("overworld"),
                new BlockPosition(x, 2, 3));
    }

    private record Fixture(LockService service, PasswordKdf kdf, LockCache cache, LockTarget target,
                           PlacementProvenance placement, UUID lockerId, UUID owner, Instant now) {
        private List<PlacementProvenance> placements() {
            return List.of(placement);
        }
    }
}
