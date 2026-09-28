package dev.chirana.umbrellaz.lock;

import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.Player;
import dev.chirana.umbrellaz.player.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockRepositoryTest {
    @Test
    void mapsPlacementAndMultiMemberLocker(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("lock.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget first = target("world", "overworld", 1, 2, 3);
        LockTarget second = target("world", "overworld", 2, 2, 3);
        UUID firstGeneration = UUID.randomUUID();
        UUID secondGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(first, firstGeneration, owner, LockBlockType.CHEST, now));
        repository.recordPlacement(new PlacementProvenance(second, secondGeneration, owner, LockBlockType.CHEST, now));
        assertEquals(owner, repository.findPlacement(first).orElseThrow().placedBy());

        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);
        UUID lockerId = UUID.randomUUID();
        LockerMetadata metadata = new LockerMetadata(lockerId, owner, kdf.hash("A123"), now, now, 7,
                Set.of(new LockerMember(lockerId, first, firstGeneration, owner, LockBlockType.CHEST),
                        new LockerMember(lockerId, second, secondGeneration, owner, LockBlockType.CHEST)));
        repository.createLocker(metadata);

        LockerMetadata stored = repository.findLocker(second).orElseThrow();
        assertEquals(lockerId, stored.lockerId());
        assertEquals(owner, stored.ownerUuid());
        assertEquals(2, repository.findAllLockers().getFirst().members().size());
        assertTrue(kdf.verify("a123", stored.password()));

        repository.removeLocker(lockerId);
        assertTrue(repository.findLocker(first).isEmpty());
        assertTrue(repository.findPlacement(first, firstGeneration).isPresent());
        assertTrue(repository.findPlacement(second, secondGeneration).isPresent());
    }

    @Test
    void rejectsMissingProvenanceAndCoordinateConflictsWithoutPartialLocker(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("conflict.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget target = target("world", "overworld", 1, 2, 3);
        UUID generation = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(target, generation, owner, LockBlockType.BARREL, now));
        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);

        UUID missingLockerId = UUID.randomUUID();
        LockerMetadata missing = metadata(missingLockerId, owner, kdf.hash("A123"),
                new LockerMember(missingLockerId, target("world", "overworld", 4, 2, 3), UUID.randomUUID(), owner,
                        LockBlockType.BARREL), now);
        assertThrows(IllegalArgumentException.class, () -> repository.createLocker(missing));
        assertTrue(repository.findAllLockers().isEmpty());

        UUID firstLockerId = UUID.randomUUID();
        repository.createLocker(metadata(firstLockerId, owner, kdf.hash("A123"),
                new LockerMember(firstLockerId, target, generation, owner, LockBlockType.BARREL), now));
        UUID conflictingLockerId = UUID.randomUUID();
        LockerMetadata conflicting = metadata(conflictingLockerId, owner, kdf.hash("A123"),
                new LockerMember(conflictingLockerId, target, generation, owner, LockBlockType.BARREL), now);
        assertThrows(Exception.class, () -> repository.createLocker(conflicting));
        assertEquals(1, repository.findAllLockers().size());
        assertTrue(repository.findLocker(target).orElseThrow().lockerId().equals(firstLockerId));
    }

    @Test
    void cacheLoadFailureLeavesServiceNotReady(@TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path occupied = directory.resolve("occupied");
        Files.writeString(occupied, "file");
        LockCache cache = new LockCache();
        LockService service = new LockService(new PasswordPolicy(),
                new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000),
                new LockPlacementPolicy(), new AttemptLimiter(), cache);
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            assertThrows(Exception.class, () -> service.loadCache(
                    new LockRepository(new SQLiteDatabase(occupied.resolve("lock.db"))), executor).join());
            assertFalse(cache.isReady());
        } finally {
            service.close();
        }
    }

    @Test
    void invalidatesWholeLogicalLockerForSingleAndDoublePlacements(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("invalidation.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);

        LockTarget singleTarget = target("world", "overworld", 4, 2, 3);
        UUID singleGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(singleTarget, singleGeneration, owner,
                LockBlockType.BARREL, now));
        UUID singleLockerId = UUID.randomUUID();
        repository.createLocker(metadata(singleLockerId, owner, kdf.hash("A123"),
                new LockerMember(singleLockerId, singleTarget, singleGeneration, owner, LockBlockType.BARREL), now));
        repository.invalidatePlacement(singleTarget, singleGeneration);
        assertTrue(repository.findPlacement(singleTarget).isEmpty());
        assertTrue(repository.findLocker(singleTarget).isEmpty());

        LockTarget first = target("world", "overworld", 8, 2, 3);
        LockTarget second = target("world", "overworld", 9, 2, 3);
        UUID firstGeneration = UUID.randomUUID();
        UUID secondGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(first, firstGeneration, owner, LockBlockType.CHEST, now));
        repository.recordPlacement(new PlacementProvenance(second, secondGeneration, owner, LockBlockType.CHEST, now));
        UUID doubleLockerId = UUID.randomUUID();
        repository.createLocker(new LockerMetadata(doubleLockerId, owner, kdf.hash("A123"), now, now, 1,
                Set.of(new LockerMember(doubleLockerId, first, firstGeneration, owner, LockBlockType.CHEST),
                        new LockerMember(doubleLockerId, second, secondGeneration, owner, LockBlockType.CHEST))));
        repository.invalidatePlacement(first, firstGeneration);
        assertTrue(repository.findLocker(first).isEmpty());
        assertTrue(repository.findLocker(second).isEmpty());
        assertTrue(repository.findPlacement(first).isEmpty());
        assertTrue(repository.findPlacement(second).isPresent());
    }

    @Test
    void replacementInvalidatesOldGenerationAndDoesNotInheritLocker(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("replacement.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget target = target("world", "overworld", 2, 2, 2);
        UUID oldGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(target, oldGeneration, owner, LockBlockType.CHEST, now));
        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);
        UUID lockerId = UUID.randomUUID();
        repository.createLocker(metadata(lockerId, owner, kdf.hash("A123"),
                new LockerMember(lockerId, target, oldGeneration, owner, LockBlockType.CHEST), now));

        UUID newGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(target, newGeneration, owner, LockBlockType.BARREL,
                now.plusSeconds(1)));

        assertTrue(repository.findPlacement(target, oldGeneration).isEmpty());
        assertEquals(newGeneration, repository.findPlacement(target).orElseThrow().generationId());
        assertTrue(repository.findLocker(target).isEmpty());
    }

    @Test
    void staleGenerationOperationsDoNotRemoveCurrentLocker(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("stale-generation.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget target = target("world", "overworld", 5, 2, 5);
        UUID generation = UUID.randomUUID();
        UUID staleGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(target, generation, owner, LockBlockType.BARREL, now));
        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);
        UUID lockerId = UUID.randomUUID();
        repository.createLocker(metadata(lockerId, owner, kdf.hash("A123"),
                new LockerMember(lockerId, target, generation, owner, LockBlockType.BARREL), now));

        assertFalse(repository.invalidatePlacementIfCurrent(target, staleGeneration));
        assertFalse(repository.removeLockerIfMembers(lockerId, List.of(
                new PlacementProvenance(target, staleGeneration, owner, LockBlockType.BARREL, now))));
        assertTrue(repository.findLocker(target).isPresent());
        assertTrue(repository.findPlacement(target, generation).isPresent());
    }

    @Test
    void restoresLockStateWhenAConfirmedInvalidationCannotBreakTheBlock(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("restore.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget target = target("world", "overworld", 4, 2, 4);
        UUID generation = UUID.randomUUID();
        PlacementProvenance placement = new PlacementProvenance(target, generation, owner,
                LockBlockType.BARREL, now);
        repository.recordPlacement(placement);
        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);
        UUID lockerId = UUID.randomUUID();
        LockerMetadata locker = metadata(lockerId, owner, kdf.hash("A123"),
                new LockerMember(lockerId, target, generation, owner, LockBlockType.BARREL), now);
        repository.createLocker(locker);

        assertTrue(repository.invalidatePlacementIfCurrent(target, generation));
        assertTrue(repository.findPlacement(target).isEmpty());
        assertTrue(repository.restoreInvalidatedState(List.of(placement), locker));
        assertEquals(generation, repository.findPlacement(target).orElseThrow().generationId());
        assertEquals(lockerId, repository.findLocker(target).orElseThrow().lockerId());
    }

    @Test
    void doesNotRestoreAStaleGenerationOverAReplacement(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("stale-restore.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget target = target("world", "overworld", 7, 2, 7);
        UUID oldGeneration = UUID.randomUUID();
        PlacementProvenance oldPlacement = new PlacementProvenance(target, oldGeneration, owner,
                LockBlockType.BARREL, now);
        repository.recordPlacement(oldPlacement);
        PasswordKdf kdf = new PasswordKdf(new PasswordPolicy(), new SecureRandom(), 16, 10_000);
        UUID lockerId = UUID.randomUUID();
        LockerMetadata locker = metadata(lockerId, owner, kdf.hash("A123"),
                new LockerMember(lockerId, target, oldGeneration, owner, LockBlockType.BARREL), now);
        repository.createLocker(locker);
        assertTrue(repository.invalidatePlacementIfCurrent(target, oldGeneration));

        UUID newGeneration = UUID.randomUUID();
        repository.recordPlacement(new PlacementProvenance(target, newGeneration, owner,
                LockBlockType.BARREL, now.plusSeconds(1)));

        assertFalse(repository.restoreInvalidatedState(List.of(oldPlacement), locker));
        assertEquals(newGeneration, repository.findPlacement(target).orElseThrow().generationId());
        assertTrue(repository.findLocker(target).isEmpty());
    }

    @Test
    void databaseExecutorOrderingRemovesAPlacementQueuedAfterItsInsert(@TempDir java.nio.file.Path directory) {
        SQLiteDatabase database = migratedDatabase(directory.resolve("ordered-invalidation.db"));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        new PlayerRepository(database).save(new Player(owner, "Owner", now, now));
        LockRepository repository = new LockRepository(database);
        LockTarget target = target("world", "overworld", 6, 2, 6);
        UUID generation = UUID.randomUUID();
        PlacementProvenance placement = new PlacementProvenance(target, generation, owner,
                LockBlockType.BARREL, now);

        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            executor.submit(() -> repository.recordPlacement(placement));
            assertTrue(executor.submit(() -> repository.invalidatePlacementIfCurrent(target, generation)).join());
        }

        assertTrue(repository.findPlacement(target).isEmpty());
    }

    private LockerMetadata metadata(UUID lockerId, UUID owner, PasswordHash password,
                                    LockerMember member, Instant now) {
        return new LockerMetadata(lockerId, owner, password, now, now, 1, Set.of(member));
    }

    private SQLiteDatabase migratedDatabase(java.nio.file.Path databasePath) {
        SQLiteDatabase database = new SQLiteDatabase(databasePath);
        MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
        database.withConnection(connection -> {
            migrations.run(connection);
            return null;
        });
        return database;
    }

    private LockTarget target(String world, String dimension, int x, int y, int z) {
        return new LockTarget(new WorldIdentity(world), new DimensionIdentity(dimension), new BlockPosition(x, y, z));
    }
}
