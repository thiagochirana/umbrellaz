package dev.chirana.umbrellaz.auth;

import dev.chirana.umbrellaz.audit.AuditRepository;
import dev.chirana.umbrellaz.audit.AuditService;
import dev.chirana.umbrellaz.audit.AuditWriter;
import dev.chirana.umbrellaz.infra.db.DatabaseExecutor;
import dev.chirana.umbrellaz.infra.db.MigrationLoader;
import dev.chirana.umbrellaz.infra.db.MigrationRunner;
import dev.chirana.umbrellaz.infra.db.sqlite.SQLiteDatabase;
import dev.chirana.umbrellaz.player.PlayerRepository;
import dev.chirana.umbrellaz.player.PlayerService;
import dev.chirana.umbrellaz.whitelist.WhitelistCache;
import dev.chirana.umbrellaz.whitelist.WhitelistRepository;
import dev.chirana.umbrellaz.whitelist.WhitelistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceAuditTest {
    @Test
    void staleJoinCannotReplaceTheCurrentSession(@TempDir Path directory) {
        try (Fixture fixture = Fixture.create(directory.resolve("stale.db"), true)) {
            UUID uuid = UUID.randomUUID();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.executor.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            await(started);

            var first = fixture.auth.onJoin(uuid, "old-name");
            var second = fixture.auth.onJoin(uuid, "new-name");
            release.countDown();

            assertFalse(first.join());
            assertFalse(second.join());
            assertEquals(AuthState.BLOCKED, fixture.auth.session(uuid).orElseThrow().state());
            assertEquals(2, fixture.auth.session(uuid).orElseThrow().generation());
        }
    }

    @Test
    void lookupFailureLeavesTheSessionBlocked(@TempDir Path directory) throws Exception {
        Path parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "occupied");
        try (DatabaseExecutor executor = new DatabaseExecutor()) {
            SQLiteDatabase database = new SQLiteDatabase(parent.resolve("broken.db"));
            PlayerService playerService = new PlayerService(new PlayerRepository(database), executor);
            WhitelistService whitelistService = new WhitelistService(new WhitelistRepository(database), playerService,
                    executor, new WhitelistCache());
            AuthService auth = new AuthService(playerService, whitelistService);

            UUID uuid = UUID.randomUUID();
            assertFalse(auth.onJoin(uuid, "Ada").join());
            assertTrue(auth.isBlocked(uuid));
        }
    }

    @Test
    void emitsLifecycleRecordsAndAuditFailureCannotAuthorizeOrBlock(@TempDir Path directory) {
        try (Fixture fixture = Fixture.create(directory.resolve("audit.db"), true)) {
            UUID uuid = UUID.randomUUID();
            assertFalse(fixture.auth.onJoin(uuid, "Ada").join());
            fixture.whitelist.add(uuid, "operator").join();
            assertTrue(fixture.auth.onJoin(uuid, "Ada").join());
            fixture.auth.onDisconnect(uuid);
            fixture.audit.flush().join();

            var actions = fixture.audit.query(dev.chirana.umbrellaz.audit.AuditQuery.defaults()).join().events().stream()
                    .map(stored -> stored.event().action())
                    .toList();
            assertTrue(actions.contains("auth.joined"));
            assertTrue(actions.contains("auth.session.changed"));
            assertTrue(actions.contains("auth.left"));

            fixture.audit.shutdown().join();
            UUID secondUuid = UUID.randomUUID();
            fixture.player.recordJoin(secondUuid, "Grace").join();
            fixture.whitelist.add(secondUuid, "operator").join();
            assertTrue(fixture.auth.onJoin(secondUuid, "Grace").join());
            assertTrue(fixture.auth.isAuthenticated(secondUuid));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final DatabaseExecutor executor;
        private final PlayerService player;
        private final WhitelistService whitelist;
        private final AuditService audit;
        private final AuthService auth;

        private Fixture(DatabaseExecutor executor, PlayerService player, WhitelistService whitelist,
                AuditService audit, AuthService auth) {
            this.executor = executor;
            this.player = player;
            this.whitelist = whitelist;
            this.audit = audit;
            this.auth = auth;
        }

        private static Fixture create(Path path, boolean withAudit) {
            DatabaseExecutor executor = new DatabaseExecutor();
            SQLiteDatabase database = new SQLiteDatabase(path);
            MigrationRunner migrations = new MigrationRunner(new MigrationLoader().load());
            executor.submit(() -> database.withConnection(connection -> {
                migrations.run(connection);
                return null;
            })).join();

            PlayerService playerService = new PlayerService(new PlayerRepository(database), executor);
            WhitelistService whitelist = new WhitelistService(new WhitelistRepository(database), playerService,
                    executor, new WhitelistCache());
            whitelist.loadCache().join();
            AuditService audit = null;
            if (withAudit) {
                AuditRepository repository = new AuditRepository(database);
                audit = new AuditService(UUID.randomUUID(), executor, repository,
                        new AuditWriter(executor, repository));
            }
            AuthService auth = audit == null
                    ? new AuthService(playerService, whitelist)
                    : new AuthService(playerService, whitelist, audit);
            return new Fixture(executor, playerService, whitelist, audit, auth);
        }

        @Override
        public void close() {
            if (audit != null) {
                audit.shutdown().join();
            }
            executor.close();
        }
    }
}
