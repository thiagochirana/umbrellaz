package dev.chirana.umbrellaz.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Server-thread-only bounded damage aggregation. It deliberately contains no
 * Minecraft objects and does not perform persistence itself.
 */
public final class DamageAuditAggregator implements AutoCloseable {
    public static final long DEFAULT_WINDOW_MS = 1_000L;
    public static final int DEFAULT_MAX_BUCKETS = 512;
    private static final long OVERFLOW_LOG_INTERVAL_MS = 60_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(DamageAuditAggregator.class);

    @FunctionalInterface
    public interface AuditSink {
        CompletionStage<?> record(AuditRecordRequest request);
    }

    private final AuditSink sink;
    private final long windowMs;
    private final int maxBuckets;
    private final Map<DamageAuditBucketKey, Bucket> buckets = new LinkedHashMap<>();
    private boolean closed;
    private long overflowDrops;
    private long closedDrops;
    private long nextOverflowLogAt = Long.MIN_VALUE;

    public DamageAuditAggregator(AuditService auditService) {
        this(auditService::record, DEFAULT_WINDOW_MS, DEFAULT_MAX_BUCKETS);
    }

    public DamageAuditAggregator(AuditService auditService, long windowMs, int maxBuckets) {
        this(auditService::record, windowMs, maxBuckets);
    }

    public DamageAuditAggregator(AuditSink sink, long windowMs, int maxBuckets) {
        this.sink = Objects.requireNonNull(sink, "Audit sink must not be null");
        if (windowMs <= 0) throw new IllegalArgumentException("Damage window must be positive");
        if (maxBuckets <= 0) throw new IllegalArgumentException("Damage bucket capacity must be positive");
        this.windowMs = windowMs;
        this.maxBuckets = maxBuckets;
    }

    /** Must be called on the Minecraft server thread. */
    public void observe(DamageAuditObservation observation, long nowMs) {
        Objects.requireNonNull(observation, "Damage observation must not be null");
        if (closed) {
            closedDrops++;
            return;
        }
        flushIfDue(nowMs);
        if (!observation.accepted()) return;
        DamageAuditBucketKey key = new DamageAuditBucketKey(observation.attackerPlayerUuid(),
                observation.victimPlayerUuid(), observation.victimEntityType(), observation.cause(),
                observation.itemType(), observation.world());
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxBuckets) {
                overflowDrops++;
                if (nowMs >= nextOverflowLogAt) {
                    nextOverflowLogAt = nowMs + OVERFLOW_LOG_INTERVAL_MS;
                    LOGGER.warn("Damage audit bucket capacity reached; dropping summaries (drops={})", overflowDrops);
                }
                return;
            }
            bucket = new Bucket(nowMs);
            buckets.put(key, bucket);
        }
        bucket.add(observation);
    }

    /** Emits due summaries without waiting or touching JDBC. */
    public int flushIfDue(long nowMs) {
        if (closed) return 0;
        return flush(nowMs, false);
    }

    /** Rejects later observations and emits all remaining summaries immediately. */
    public int closeAndFlush(long nowMs) {
        if (closed) return 0;
        closed = true;
        return flush(nowMs, true);
    }

    private int flush(long nowMs, boolean all) {
        List<Summary> summaries = new ArrayList<>();
        var iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DamageAuditBucketKey, Bucket> entry = iterator.next();
            Bucket bucket = entry.getValue();
            if (all || nowMs - bucket.startedAt >= windowMs) {
                summaries.add(new Summary(entry.getKey(), bucket));
                iterator.remove();
            }
        }
        for (Summary summary : summaries) {
            submit(summary, Math.max(0L, nowMs - summary.bucket.startedAt));
        }
        return summaries.size();
    }

    private void submit(Summary summary, long durationMs) {
        try {
            DamageAuditBucketKey key = summary.key;
            List<AuditPayload.Field> fields = new ArrayList<>();
            fields.add(AuditPayload.count(summary.bucket.countAsInt()));
            fields.add(AuditPayload.decimal("base_damage", summary.bucket.baseDamage));
            fields.add(AuditPayload.decimal("reported_damage", summary.bucket.reportedDamage));
            fields.add(AuditPayload.integer("window_duration_ms", boundedInt(durationMs)));
            fields.add(AuditPayload.cause(key.cause()));
            fields.add(AuditPayload.itemType(key.itemType()));
            fields.add(AuditPayload.world(key.world()));
            if (key.attackerPlayerUuid() != null) {
                fields.add(AuditPayload.uuid("attacker_uuid", key.attackerPlayerUuid()));
            }
            if (key.victimPlayerUuid() != null) {
                fields.add(AuditPayload.uuid("victim_uuid", key.victimPlayerUuid()));
            }
            if (key.victimEntityType() != null) {
                fields.add(AuditPayload.victimEntityType(key.victimEntityType()));
            }
            AuditPayload payload = AuditPayload.forAction(AuditActions.DAMAGE_SUMMARY,
                    fields.toArray(AuditPayload.Field[]::new));
            Target target = key.victimPlayerUuid() == null
                    ? new Target("entity_type", key.victimEntityType())
                    : new Target("player", key.victimPlayerUuid().toString());
            Actor actor = key.attackerPlayerUuid() == null
                    ? new Actor(ActorType.SYSTEM, null, "server")
                    : new Actor(ActorType.PLAYER, key.attackerPlayerUuid(), null);
            AuditRecordRequest request = new AuditRecordRequest(
                    AuditRecordContext.forActor(actor), Source.EVENT,
                    AuditActions.DAMAGE_SUMMARY, Outcome.SUCCESS, target, "damage_summary", 1, payload,
                    AuditDelivery.BEST_EFFORT);
            sink.record(request).exceptionally(failure -> null);
        } catch (RuntimeException failure) {
            LOGGER.debug("Damage audit summary could not be submitted", failure);
        }
    }

    private static int boundedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    public int bucketCount() { return buckets.size(); }
    public long overflowDrops() { return overflowDrops; }
    public long closedDrops() { return closedDrops; }
    public long droppedObservations() { return overflowDrops + closedDrops; }
    public boolean closed() { return closed; }
    public long windowMs() { return windowMs; }
    public int maxBuckets() { return maxBuckets; }

    @Override
    public void close() {
        closeAndFlush(System.currentTimeMillis());
    }

    private record Summary(DamageAuditBucketKey key, Bucket bucket) {}

    private static final class Bucket {
        private final long startedAt;
        private long count;
        private double baseDamage;
        private double reportedDamage;

        private Bucket(long startedAt) { this.startedAt = startedAt; }

        private void add(DamageAuditObservation observation) {
            if (count < Integer.MAX_VALUE) count++;
            baseDamage = saturatedAdd(baseDamage, observation.baseDamage());
            reportedDamage = saturatedAdd(reportedDamage, observation.reportedDamage());
        }

        private int countAsInt() { return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count; }

        private static double saturatedAdd(double left, double right) {
            double value = left + right;
            return Double.isFinite(value) ? value : Double.MAX_VALUE;
        }
    }
}
