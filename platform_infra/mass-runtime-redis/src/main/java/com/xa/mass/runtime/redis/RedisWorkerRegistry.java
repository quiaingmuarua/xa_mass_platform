package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.xa.mass.runtime.worker.CleanupSummary;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.RandomWorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.ReserveResult;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerCandidateSamplingContext;
import com.xa.mass.runtime.worker.WorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicies;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicy;
import com.xa.mass.runtime.worker.WorkerSlot;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Redis-backed WorkerRegistry using group-partitioned slot hashes and buckets.
 *
 * <p>Slot mutations use Redis WATCH/MULTI so capacity and occupancy updates are
 * atomic across Redis clients without introducing an engine-local global lock.</p>
 */
public final class RedisWorkerRegistry implements WorkerRegistry, AutoCloseable {

    public static final String DEFAULT_ROUTE_BUCKET_KEY = WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY;
    public static final long DEFAULT_HEARTBEAT_FRESHNESS_MILLIS = 30_000L;

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_WATCH_RETRIES = 32;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisWorkerRegistryKeyspace keyspace;
    private final WorkerCandidateSamplingPolicy samplingPolicy;
    private final WorkerRouteBucketPolicy routeBucketPolicy;
    private final long heartbeatFreshnessMillis;
    private final boolean ownsClient;

    public RedisWorkerRegistry(String redisUri, String namespace) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                new RedisWorkerRegistryKeyspace(namespace),
                RandomWorkerCandidateSamplingPolicy.defaultPolicy(),
                WorkerRouteBucketPolicies.defaultPolicy(),
                DEFAULT_HEARTBEAT_FRESHNESS_MILLIS,
                true);
    }

    public RedisWorkerRegistry(RedisClient redisClient,
                               RedisWorkerRegistryKeyspace keyspace,
                               WorkerCandidateSamplingPolicy samplingPolicy,
                               WorkerRouteBucketPolicy routeBucketPolicy,
                               boolean ownsClient) {
        this(redisClient,
                keyspace,
                samplingPolicy,
                routeBucketPolicy,
                DEFAULT_HEARTBEAT_FRESHNESS_MILLIS,
                ownsClient);
    }

    public RedisWorkerRegistry(RedisClient redisClient,
                               RedisWorkerRegistryKeyspace keyspace,
                               WorkerCandidateSamplingPolicy samplingPolicy,
                               WorkerRouteBucketPolicy routeBucketPolicy,
                               long heartbeatFreshnessMillis,
                               boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                keyspace,
                samplingPolicy,
                routeBucketPolicy,
                heartbeatFreshnessMillis,
                ownsClient);
    }

    public RedisWorkerRegistry(StatefulRedisConnection<String, String> connection,
                               RedisWorkerRegistryKeyspace keyspace,
                               WorkerCandidateSamplingPolicy samplingPolicy,
                               WorkerRouteBucketPolicy routeBucketPolicy) {
        this(null,
                connection,
                keyspace,
                samplingPolicy,
                routeBucketPolicy,
                DEFAULT_HEARTBEAT_FRESHNESS_MILLIS,
                false);
    }

    private RedisWorkerRegistry(RedisClient redisClient,
                                StatefulRedisConnection<String, String> connection,
                                RedisWorkerRegistryKeyspace keyspace,
                                WorkerCandidateSamplingPolicy samplingPolicy,
                                WorkerRouteBucketPolicy routeBucketPolicy,
                                long heartbeatFreshnessMillis,
                                boolean ownsClient) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.keyspace = keyspace != null ? keyspace : new RedisWorkerRegistryKeyspace();
        this.samplingPolicy = samplingPolicy != null
                ? samplingPolicy
                : RandomWorkerCandidateSamplingPolicy.defaultPolicy();
        this.routeBucketPolicy = routeBucketPolicy != null
                ? routeBucketPolicy
                : WorkerRouteBucketPolicies.defaultPolicy();
        this.heartbeatFreshnessMillis = Math.max(1L, heartbeatFreshnessMillis);
        this.ownsClient = ownsClient;
    }

    @Override
    public void upsertSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling) {
        Objects.requireNonNull(meta, "meta");
        String workerId = meta.workerId();
        String groupId = meta.groupId();
        String previousGroupId = commands.hget(keyspace.workerGroupHash(), workerId);
        if (previousGroupId != null && !previousGroupId.equals(groupId)) {
            removeFromBuckets(previousGroupId, workerId);
            commands.hdel(keyspace.groupSlotsHash(previousGroupId), workerId);
        }

        updateSlot(groupId, workerId, current -> {
            WorkerSlot updated = current == null
                    ? newSlot(meta, declaredCapacity, eventBindingCeiling)
                    : new WorkerSlot(
                            meta,
                            declaredCapacity,
                            eventBindingCeiling,
                            current.activeLeaseCount(),
                            current.reservedCount(),
                            current.activeLeaseCountByTask(),
                            current.disabledSources(),
                            current.exclusiveLeaseHeld(),
                            current.removing(),
                            current.removingReason()
                    );
            return SlotUpdate.write(updated, tx -> {
                tx.hset(keyspace.workerGroupHash(), workerId, groupId);
                tx.zadd(keyspace.heartbeatDeadlinesZset(),
                        heartbeatDeadlineMillis(meta),
                        keyspace.heartbeatMember(groupId, workerId));
            });
        });
        removeFromBuckets(groupId, workerId);
        addToBuckets(meta);
    }

    @Override
    public boolean markSlotRemoving(String groupId, String workerId, String reason) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return false;
        }
        MutationResult result = updateSlot(normalizedGroupId, normalizedWorkerId, current -> {
            if (current == null || current.removing()) {
                return SlotUpdate.noop(current, false);
            }
            return SlotUpdate.write(new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    current.reservedCount(),
                    current.activeLeaseCountByTask(),
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    true,
                    reason
            ), null, true);
        });
        if (result.changed()) {
            removeFromBuckets(normalizedGroupId, normalizedWorkerId);
        }
        return result.changed();
    }

    @Override
    public CleanupSummary cleanupRemovedSlots(String groupId, int limit) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null || limit <= 0) {
            return CleanupSummary.empty();
        }
        List<String> workerIds = commands.hkeys(keyspace.groupSlotsHash(normalizedGroupId));
        int scanned = 0;
        int removed = 0;
        int skipped = 0;
        for (String workerId : workerIds) {
            if (scanned >= limit) {
                break;
            }
            scanned++;
            WorkerSlot slot = slot(normalizedGroupId, workerId).orElse(null);
            if (slot == null || !slot.removing() || slot.occupiedPermits() > 0) {
                skipped++;
                continue;
            }
            commands.hdel(keyspace.groupSlotsHash(normalizedGroupId), workerId);
            commands.hdel(keyspace.workerGroupHash(), workerId);
            commands.zrem(keyspace.heartbeatDeadlinesZset(), keyspace.heartbeatMember(normalizedGroupId, workerId));
            commands.srem(keyspace.exclusiveLeasesSet(), workerId);
            removeFromBuckets(normalizedGroupId, workerId);
            removed++;
        }
        return new CleanupSummary(scanned, removed, skipped);
    }

    @Override
    public Optional<WorkerSlot> slot(String groupId, String workerId) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(decodeSlot(commands.hget(keyspace.groupSlotsHash(normalizedGroupId), normalizedWorkerId)));
    }

    @Override
    public Optional<WorkerSlot> slotByWorkerId(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return Optional.empty();
        }
        String groupId = commands.hget(keyspace.workerGroupHash(), normalizedWorkerId);
        return groupId == null ? Optional.empty() : slot(groupId, normalizedWorkerId);
    }

    @Override
    public Set<String> workerIdsByGroupId(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return Set.of();
        }
        return Set.copyOf(commands.hkeys(keyspace.groupSlotsHash(normalizedGroupId)));
    }

    @Override
    public Set<String> workerIdsByAdapterNodeGroup(String adapterNodeId, String groupId) {
        String normalizedAdapterNodeId = normalizeNullable(adapterNodeId);
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedAdapterNodeId == null || normalizedGroupId == null) {
            return Set.of();
        }
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (String nodeRouteMember : commands.smembers(keyspace.groupNodeRoutesSet(normalizedGroupId))) {
            try {
                RedisWorkerRegistryKeyspace.NodeRouteMember parsed = keyspace.parseNodeRouteMember(nodeRouteMember);
                if (normalizedAdapterNodeId.equals(parsed.adapterNodeId())) {
                    workerIds.addAll(commands.smembers(
                            keyspace.nodeRouteBucket(normalizedGroupId, parsed.adapterNodeId(), parsed.routeBucketKey())
                    ));
                }
            } catch (IllegalArgumentException ignored) {
                // Stale malformed diagnostic member; bounded cleanup can remove it later.
            }
        }
        return Set.copyOf(workerIds);
    }

    @Override
    public List<String> acquireCandidates(String groupId, String routeBucketKey, int maxCandidateCount) {
        return acquireCandidates(groupId, null, routeBucketKey, maxCandidateCount);
    }

    @Override
    public List<String> acquireCandidates(String groupId,
                                          String adapterNodeId,
                                          String routeBucketKey,
                                          int maxCandidateCount) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedRouteBucketKey = normalizeRouteBucketKey(routeBucketKey);
        String normalizedAdapterNodeId = normalizeNullable(adapterNodeId);
        if (normalizedGroupId == null || maxCandidateCount <= 0) {
            return List.of();
        }
        String bucketKey = normalizedAdapterNodeId == null
                ? keyspace.groupRouteBucket(normalizedGroupId, normalizedRouteBucketKey)
                : keyspace.nodeRouteBucket(normalizedGroupId, normalizedAdapterNodeId, normalizedRouteBucketKey);
        List<String> workerIds = new ArrayList<>(commands.smembers(bucketKey));
        workerIds.sort(String::compareTo);
        return samplingPolicy.sample(
                new WorkerCandidateSamplingContext(normalizedGroupId, normalizedAdapterNodeId, normalizedRouteBucketKey),
                workerIds,
                maxCandidateCount
        );
    }

    @Override
    public ReserveResult tryReserve(String groupId, String workerId, String taskId, int permits, long nowMillis) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        int normalizedPermits = Math.max(1, permits);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return ReserveResult.rejected(ReserveStatus.MISSING_SLOT, "worker slot missing");
        }
        if (slot(normalizedGroupId, normalizedWorkerId).isEmpty()) {
            return slotByWorkerId(normalizedWorkerId).isPresent()
                    ? ReserveResult.rejected(ReserveStatus.GROUP_MISMATCH, "worker group mismatch")
                    : ReserveResult.rejected(ReserveStatus.MISSING_SLOT, "worker slot missing");
        }
        final ReserveResult[] accepted = new ReserveResult[1];
        MutationResult result = updateSlot(normalizedGroupId, normalizedWorkerId, current -> {
            ReserveStatus status = validateReserve(current, normalizedGroupId, normalizedWorkerId, normalizedPermits, nowMillis);
            if (status != ReserveStatus.ACCEPTED) {
                return SlotUpdate.noop(current, ReserveResult.rejected(status, status.name()));
            }
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    current.reservedCount() + normalizedPermits,
                    current.activeLeaseCountByTask(),
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            accepted[0] = ReserveResult.accepted(updated);
            return SlotUpdate.write(updated, null, accepted[0]);
        });
        return result.payload() instanceof ReserveResult reserveResult
                ? reserveResult
                : accepted[0] == null
                        ? ReserveResult.rejected(ReserveStatus.MISSING_SLOT, "worker slot missing")
                        : accepted[0];
    }

    @Override
    public boolean confirmReservation(String groupId, String workerId, String taskId, int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedWorkerId = normalizeNullable(workerId);
        int normalizedPermits = Math.max(1, permits);
        MutationResult result = updateSlot(groupId, workerId, current -> {
            if (current == null || current.removing() || current.reservedCount() < normalizedPermits) {
                return SlotUpdate.noop(current, false);
            }
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount() + normalizedPermits,
                    current.reservedCount() - normalizedPermits,
                    incrementTaskCount(current.activeLeaseCountByTask(), normalizedTaskId, normalizedPermits),
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated,
                    tx -> incrementTaskProjection(tx, normalizedTaskId, normalizedWorkerId, normalizedPermits),
                    true);
        });
        return Boolean.TRUE.equals(result.payload());
    }

    @Override
    public void releaseReservation(String groupId, String workerId, String taskId, int permits) {
        int normalizedPermits = Math.max(1, permits);
        updateSlot(groupId, workerId, current -> {
            if (current == null) {
                return SlotUpdate.noop(null, false);
            }
            return SlotUpdate.write(new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    Math.max(0, current.reservedCount() - normalizedPermits),
                    current.activeLeaseCountByTask(),
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            ), null);
        });
    }

    @Override
    public void recordWorkClaimed(String groupId, String workerId, String taskId, int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedWorkerId = normalizeNullable(workerId);
        int normalizedPermits = Math.max(1, permits);
        updateSlot(groupId, workerId, current -> {
            if (current == null) {
                return SlotUpdate.noop(null, false);
            }
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount() + normalizedPermits,
                    current.reservedCount(),
                    incrementTaskCount(current.activeLeaseCountByTask(), normalizedTaskId, normalizedPermits),
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated,
                    tx -> incrementTaskProjection(tx, normalizedTaskId, normalizedWorkerId, normalizedPermits),
                    true);
        });
    }

    @Override
    public void recordWorkFinal(String groupId, String workerId, String taskId, int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedWorkerId = normalizeNullable(workerId);
        int normalizedPermits = Math.max(1, permits);
        updateSlot(groupId, workerId, current -> {
            if (current == null) {
                return SlotUpdate.noop(null, false);
            }
            int released = Math.min(normalizedPermits, current.activeLeaseCount());
            if (released <= 0) {
                return SlotUpdate.noop(current, false);
            }
            int nextTaskCount = Math.max(0, current.activeLeaseCountByTask().getOrDefault(normalizedTaskId, 0) - released);
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    Math.max(0, current.activeLeaseCount() - released),
                    current.reservedCount(),
                    decrementTaskCount(current.activeLeaseCountByTask(), normalizedTaskId, released),
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated,
                    tx -> decrementTaskProjection(tx, normalizedTaskId, normalizedWorkerId, released, nextTaskCount),
                    true);
        });
    }

    @Override
    public boolean tryAcquireExclusiveLease(String groupId, String workerId) {
        MutationResult result = updateSlot(groupId, workerId, current -> {
            if (current == null || current.removing() || current.exclusiveLeaseHeld()) {
                return SlotUpdate.noop(current, false);
            }
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    current.reservedCount(),
                    current.activeLeaseCountByTask(),
                    current.disabledSources(),
                    true,
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated, tx -> tx.sadd(keyspace.exclusiveLeasesSet(), current.workerId()), true);
        });
        return Boolean.TRUE.equals(result.payload());
    }

    @Override
    public void releaseExclusiveLease(String groupId, String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        updateSlot(groupId, workerId, current -> {
            if (current == null || !current.exclusiveLeaseHeld()) {
                return SlotUpdate.noop(current, false);
            }
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    current.reservedCount(),
                    current.activeLeaseCountByTask(),
                    current.disabledSources(),
                    false,
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated, tx -> tx.srem(keyspace.exclusiveLeasesSet(), normalizedWorkerId), true);
        });
    }

    @Override
    public boolean hasExclusiveLease(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        return normalizedWorkerId != null && Boolean.TRUE.equals(commands.sismember(keyspace.exclusiveLeasesSet(), normalizedWorkerId));
    }

    @Override
    public List<String> exclusiveLeaseWorkerIds() {
        List<String> workerIds = new ArrayList<>(commands.smembers(keyspace.exclusiveLeasesSet()));
        workerIds.sort(String::compareTo);
        return List.copyOf(workerIds);
    }

    @Override
    public boolean disableDispatch(String groupId, String workerId, DispatchAvailabilitySource source) {
        Objects.requireNonNull(source, "source");
        MutationResult result = updateSlot(groupId, workerId, current -> {
            if (current == null) {
                return SlotUpdate.noop(null, false);
            }
            EnumSet<DispatchAvailabilitySource> sources = disabledSources(current);
            boolean changed = sources.add(source);
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    current.reservedCount(),
                    current.activeLeaseCountByTask(),
                    sources,
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated, null, changed);
        });
        return Boolean.TRUE.equals(result.payload());
    }

    @Override
    public boolean clearDispatchDisable(String groupId, String workerId, DispatchAvailabilitySource source) {
        Objects.requireNonNull(source, "source");
        MutationResult result = updateSlot(groupId, workerId, current -> {
            if (current == null) {
                return SlotUpdate.noop(null, false);
            }
            EnumSet<DispatchAvailabilitySource> sources = disabledSources(current);
            boolean changed = sources.remove(source);
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount(),
                    current.reservedCount(),
                    current.activeLeaseCountByTask(),
                    sources,
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            return SlotUpdate.write(updated, null, changed);
        });
        return Boolean.TRUE.equals(result.payload());
    }

    @Override
    public Set<String> activeWorkerIdsByTask(String taskId) {
        String normalizedTaskId = normalizeNullable(taskId);
        if (normalizedTaskId == null) {
            return Set.of();
        }
        return Set.copyOf(commands.smembers(keyspace.taskActiveWorkersSet(normalizedTaskId)));
    }

    @Override
    public int activeWorkerCountForTask(String taskId) {
        return activeWorkerIdsByTask(taskId).size();
    }

    @Override
    public int activeLeaseCountByTaskWorker(String taskId, String workerId) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedTaskId == null || normalizedWorkerId == null) {
            return 0;
        }
        String value = commands.hget(keyspace.taskWorkerActiveCountsHash(normalizedTaskId), normalizedWorkerId);
        return parseNonNegativeInt(value);
    }

    @Override
    public void markCandidateStale(String groupId, String workerId, String reason) {
        removeFromBuckets(normalizeNullable(groupId), normalizeNullable(workerId));
    }

    @Override
    public CleanupSummary cleanupExpiredHeartbeats(long nowMillis, int limit) {
        if (limit <= 0) {
            return CleanupSummary.empty();
        }
        List<String> members = commands.zrangebyscore(
                keyspace.heartbeatDeadlinesZset(),
                Range.create(Double.NEGATIVE_INFINITY, (double) nowMillis)
        );
        int scanned = 0;
        int removed = 0;
        int skipped = 0;
        for (String member : members) {
            if (scanned >= limit) {
                break;
            }
            scanned++;
            try {
                RedisWorkerRegistryKeyspace.HeartbeatMember parsed = keyspace.parseHeartbeatMember(member);
                boolean changed = markSlotRemoving(parsed.groupId(), parsed.workerId(), "heartbeat expired");
                removeFromBuckets(parsed.groupId(), parsed.workerId());
                commands.zrem(keyspace.heartbeatDeadlinesZset(), member);
                if (changed) {
                    removed++;
                } else {
                    skipped++;
                }
            } catch (IllegalArgumentException ignored) {
                skipped++;
            }
        }
        return new CleanupSummary(scanned, removed, skipped);
    }

    @Override
    public CleanupSummary cleanupStaleBucketMembers(String groupId, int limit) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null || limit <= 0) {
            return CleanupSummary.empty();
        }
        int scanned = 0;
        int removed = 0;
        for (String routeBucketKey : commands.smembers(keyspace.groupRoutesSet(normalizedGroupId))) {
            String bucketKey = keyspace.groupRouteBucket(normalizedGroupId, routeBucketKey);
            for (String workerId : List.copyOf(commands.smembers(bucketKey))) {
                if (scanned >= limit) {
                    return new CleanupSummary(scanned, removed, 0);
                }
                scanned++;
                if (slot(normalizedGroupId, workerId).isEmpty()) {
                    commands.srem(bucketKey, workerId);
                    removed++;
                }
            }
        }
        return new CleanupSummary(scanned, removed, 0);
    }

    @Override
    public void close() {
        if (connection.isOpen()) {
            connection.close();
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    private synchronized MutationResult updateSlot(String groupId, String workerId, SlotMutation mutation) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return new MutationResult(false, null);
        }
        String slotsKey = keyspace.groupSlotsHash(normalizedGroupId);
        for (int attempt = 0; attempt < DEFAULT_WATCH_RETRIES; attempt++) {
            commands.watch(slotsKey);
            WorkerSlot current = decodeSlot(commands.hget(slotsKey, normalizedWorkerId));
            SlotUpdate update = mutation.apply(current);
            if (!update.write()) {
                commands.unwatch();
                return new MutationResult(update.changed(), update.payload());
            }
            commands.multi();
            commands.hset(slotsKey, normalizedWorkerId, encodeSlot(update.updated()));
            if (update.extraCommands() != null) {
                update.extraCommands().accept(commands);
            }
            TransactionResult result = commands.exec();
            if (result != null && !result.wasDiscarded()) {
                return new MutationResult(update.changed(), update.payload());
            }
        }
        throw new IllegalStateException("Redis worker slot update exceeded retry limit");
    }

    private WorkerSlot decodeSlot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        SlotPayload payload = GSON.fromJson(json, SlotPayload.class);
        return payload == null ? null : payload.toSlot();
    }

    private String encodeSlot(WorkerSlot slot) {
        return GSON.toJson(SlotPayload.from(slot));
    }

    private WorkerSlot newSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling) {
        return new WorkerSlot(
                meta,
                declaredCapacity,
                eventBindingCeiling,
                0,
                0,
                Map.of(),
                Set.of(),
                false,
                false,
                null
        );
    }

    private ReserveStatus validateReserve(WorkerSlot current,
                                          String groupId,
                                          String workerId,
                                          int permits,
                                          long nowMillis) {
        if (current == null) {
            return ReserveStatus.MISSING_SLOT;
        }
        if (!current.groupId().equals(groupId) || !current.workerId().equals(workerId)) {
            return ReserveStatus.GROUP_MISMATCH;
        }
        if (current.removing()) {
            return ReserveStatus.REMOVING_SLOT;
        }
        if (heartbeatDeadlineMillis(current.meta()) <= nowMillis) {
            return ReserveStatus.STALE_HEARTBEAT;
        }
        if (!current.dispatchEnabled()) {
            return ReserveStatus.DISPATCH_DISABLED;
        }
        if (current.occupiedPermits() + permits > current.declaredCapacity()) {
            return ReserveStatus.CAPACITY_UNAVAILABLE;
        }
        return ReserveStatus.ACCEPTED;
    }

    private void addToBuckets(WorkerMeta meta) {
        LinkedHashSet<String> bucketKeys = new LinkedHashSet<>();
        for (String routeBucketKey : routeBucketKeys(meta)) {
            commands.sadd(keyspace.groupRoutesSet(meta.groupId()), routeBucketKey);
            String groupBucketKey = keyspace.groupRouteBucket(meta.groupId(), routeBucketKey);
            commands.sadd(groupBucketKey, meta.workerId());
            bucketKeys.add(groupBucketKey);
            if (meta.adapterNodeId() != null) {
                commands.sadd(keyspace.groupNodeRoutesSet(meta.groupId()), keyspace.nodeRouteMember(meta.adapterNodeId(), routeBucketKey));
                String nodeBucketKey = keyspace.nodeRouteBucket(meta.groupId(), meta.adapterNodeId(), routeBucketKey);
                commands.sadd(nodeBucketKey, meta.workerId());
                bucketKeys.add(nodeBucketKey);
            }
        }
        if (!bucketKeys.isEmpty()) {
            commands.sadd(keyspace.workerBucketMembershipSet(meta.groupId(), meta.workerId()), bucketKeys.toArray(String[]::new));
        }
    }

    private void removeFromBuckets(String groupId, String workerId) {
        if (groupId == null || workerId == null) {
            return;
        }
        String membershipKey = keyspace.workerBucketMembershipSet(groupId, workerId);
        Set<String> bucketKeys = commands.smembers(membershipKey);
        for (String bucketKey : bucketKeys) {
            commands.srem(bucketKey, workerId);
        }
        commands.del(membershipKey);
    }

    private Set<String> routeBucketKeys(WorkerMeta meta) {
        Set<String> routeBucketKeys = routeBucketPolicy.routeBucketKeysForWorkerMeta(meta);
        return routeBucketKeys == null || routeBucketKeys.isEmpty()
                ? Set.of(DEFAULT_ROUTE_BUCKET_KEY)
                : Set.copyOf(routeBucketKeys);
    }

    private long heartbeatDeadlineMillis(WorkerMeta meta) {
        long lastHeartbeatMillis = Math.max(0L, meta.lastHeartbeatMillis());
        long maxIncrement = Long.MAX_VALUE - lastHeartbeatMillis;
        return lastHeartbeatMillis + Math.min(heartbeatFreshnessMillis, maxIncrement);
    }

    private void incrementTaskProjection(RedisCommands<String, String> tx, String taskId, String workerId, int permits) {
        if (taskId == null || workerId == null) {
            return;
        }
        tx.sadd(keyspace.taskActiveWorkersSet(taskId), workerId);
        tx.hincrby(keyspace.taskWorkerActiveCountsHash(taskId), workerId, permits);
    }

    private void decrementTaskProjection(RedisCommands<String, String> tx,
                                         String taskId,
                                         String workerId,
                                         int permits,
                                         int nextTaskCount) {
        if (taskId == null || workerId == null) {
            return;
        }
        if (nextTaskCount <= 0) {
            tx.hdel(keyspace.taskWorkerActiveCountsHash(taskId), workerId);
            tx.srem(keyspace.taskActiveWorkersSet(taskId), workerId);
        } else {
            tx.hincrby(keyspace.taskWorkerActiveCountsHash(taskId), workerId, -permits);
        }
    }

    private static Map<String, Integer> incrementTaskCount(Map<String, Integer> current,
                                                           String taskId,
                                                           int permits) {
        if (taskId == null) {
            return current;
        }
        LinkedHashMap<String, Integer> updated = new LinkedHashMap<>(current);
        updated.merge(taskId, permits, Integer::sum);
        return Map.copyOf(updated);
    }

    private static Map<String, Integer> decrementTaskCount(Map<String, Integer> current,
                                                           String taskId,
                                                           int permits) {
        if (taskId == null || current.isEmpty()) {
            return current;
        }
        LinkedHashMap<String, Integer> updated = new LinkedHashMap<>(current);
        updated.computeIfPresent(taskId, (ignored, value) -> {
            int next = Math.max(0, value - permits);
            return next == 0 ? null : next;
        });
        return Map.copyOf(updated);
    }

    private static EnumSet<DispatchAvailabilitySource> disabledSources(WorkerSlot slot) {
        return slot.disabledSources().isEmpty()
                ? EnumSet.noneOf(DispatchAvailabilitySource.class)
                : EnumSet.copyOf(slot.disabledSources());
    }

    private static int parseNonNegativeInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(0, Integer.parseInt(value));
    }

    private static String normalizeRouteBucketKey(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? DEFAULT_ROUTE_BUCKET_KEY : normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MutationResult(boolean changed, Object payload) {
    }

    private record SlotUpdate(boolean write,
                              WorkerSlot updated,
                              Consumer<RedisCommands<String, String>> extraCommands,
                              boolean changed,
                              Object payload) {

        static SlotUpdate write(WorkerSlot updated, Consumer<RedisCommands<String, String>> extraCommands) {
            return write(updated, extraCommands, true);
        }

        static SlotUpdate write(WorkerSlot updated,
                                Consumer<RedisCommands<String, String>> extraCommands,
                                Object payload) {
            return new SlotUpdate(true, updated, extraCommands, true, payload);
        }

        static SlotUpdate noop(WorkerSlot current, Object payload) {
            return new SlotUpdate(false, current, null, false, payload);
        }
    }

    private interface SlotMutation {
        SlotUpdate apply(WorkerSlot current);
    }

    private static final class SlotPayload {
        WorkerMetaPayload meta;
        int declaredCapacity;
        List<EventKeyPayload> eventBindingCeiling;
        int activeLeaseCount;
        int reservedCount;
        Map<String, Integer> activeLeaseCountByTask;
        List<String> disabledSources;
        boolean exclusiveLeaseHeld;
        boolean removing;
        String removingReason;

        static SlotPayload from(WorkerSlot slot) {
            SlotPayload payload = new SlotPayload();
            payload.meta = WorkerMetaPayload.from(slot.meta());
            payload.declaredCapacity = slot.declaredCapacity();
            payload.eventBindingCeiling = slot.eventBindingCeiling().stream()
                    .map(EventKeyPayload::from)
                    .toList();
            payload.activeLeaseCount = slot.activeLeaseCount();
            payload.reservedCount = slot.reservedCount();
            payload.activeLeaseCountByTask = slot.activeLeaseCountByTask();
            payload.disabledSources = slot.disabledSources().stream()
                    .map(Enum::name)
                    .toList();
            payload.exclusiveLeaseHeld = slot.exclusiveLeaseHeld();
            payload.removing = slot.removing();
            payload.removingReason = slot.removingReason();
            return payload;
        }

        WorkerSlot toSlot() {
            return new WorkerSlot(
                    meta.toMeta(),
                    declaredCapacity,
                    eventKeys(eventBindingCeiling),
                    activeLeaseCount,
                    reservedCount,
                    activeLeaseCountByTask,
                    dispatchSources(disabledSources),
                    exclusiveLeaseHeld,
                    removing,
                    removingReason
            );
        }
    }

    private static final class WorkerMetaPayload {
        String workerId;
        String groupId;
        String adapterNodeId;
        String adapterId;
        String transportHint;
        Map<String, String> attributes;
        String agentVersion;
        String runtimeVersion;
        long lastHeartbeatMillis;
        String diagnosticStatus;

        static WorkerMetaPayload from(WorkerMeta meta) {
            WorkerMetaPayload payload = new WorkerMetaPayload();
            payload.workerId = meta.workerId();
            payload.groupId = meta.groupId();
            payload.adapterNodeId = meta.adapterNodeId();
            payload.adapterId = meta.adapterId();
            payload.transportHint = meta.transportHint();
            payload.attributes = meta.attributes();
            payload.agentVersion = meta.agentVersion();
            payload.runtimeVersion = meta.runtimeVersion();
            payload.lastHeartbeatMillis = meta.lastHeartbeatMillis();
            payload.diagnosticStatus = meta.diagnosticStatus();
            return payload;
        }

        WorkerMeta toMeta() {
            return new WorkerMeta(
                    workerId,
                    groupId,
                    adapterNodeId,
                    adapterId,
                    transportHint,
                    attributes,
                    agentVersion,
                    runtimeVersion,
                    lastHeartbeatMillis,
                    diagnosticStatus
            );
        }
    }

    private static final class EventKeyPayload {
        String projectCode;
        String eventCode;

        static EventKeyPayload from(EventKey key) {
            EventKeyPayload payload = new EventKeyPayload();
            payload.projectCode = key.projectCode();
            payload.eventCode = key.eventCode();
            return payload;
        }

        EventKey toEventKey() {
            return new EventKey(projectCode, eventCode);
        }
    }

    private static Set<EventKey> eventKeys(List<EventKeyPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<EventKey> eventKeys = new LinkedHashSet<>();
        for (EventKeyPayload payload : payloads) {
            if (payload != null) {
                eventKeys.add(payload.toEventKey());
            }
        }
        return Set.copyOf(eventKeys);
    }

    private static Set<DispatchAvailabilitySource> dispatchSources(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        EnumSet<DispatchAvailabilitySource> sources = EnumSet.noneOf(DispatchAvailabilitySource.class);
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                sources.add(DispatchAvailabilitySource.valueOf(name));
            }
        }
        return sources.isEmpty() ? Set.of() : Set.copyOf(sources);
    }
}
