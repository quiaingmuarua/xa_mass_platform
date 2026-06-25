package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.xa.mass.runtime.worker.CleanupSummary;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.runtime.worker.WorkerSlot;
import io.lettuce.core.Limit;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Redis-backed WorkerRegistry using group-partitioned slot hashes.
 *
 * <p>Slot mutations use Redis WATCH/MULTI so capacity and occupancy updates are
 * atomic across Redis clients without introducing an engine-local global lock.</p>
 */
public final class RedisWorkerRegistry implements WorkerRegistry, AutoCloseable {

    public static final long DEFAULT_HEARTBEAT_FRESHNESS_MILLIS = 30_000L;

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_WATCH_RETRIES = 32;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisWorkerRegistryKeyspace keyspace;
    private final long heartbeatFreshnessMillis;
    private final boolean ownsClient;

    public RedisWorkerRegistry(String redisUri, String namespace) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                new RedisWorkerRegistryKeyspace(namespace),
                DEFAULT_HEARTBEAT_FRESHNESS_MILLIS,
                true);
    }

    public RedisWorkerRegistry(RedisClient redisClient,
                               RedisWorkerRegistryKeyspace keyspace,
                               boolean ownsClient) {
        this(redisClient,
                keyspace,
                DEFAULT_HEARTBEAT_FRESHNESS_MILLIS,
                ownsClient);
    }

    public RedisWorkerRegistry(RedisClient redisClient,
                               RedisWorkerRegistryKeyspace keyspace,
                               long heartbeatFreshnessMillis,
                               boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                keyspace,
                heartbeatFreshnessMillis,
                ownsClient);
    }

    public RedisWorkerRegistry(StatefulRedisConnection<String, String> connection,
                               RedisWorkerRegistryKeyspace keyspace) {
        this(null,
                connection,
                keyspace,
                DEFAULT_HEARTBEAT_FRESHNESS_MILLIS,
                false);
    }

    private RedisWorkerRegistry(RedisClient redisClient,
                                StatefulRedisConnection<String, String> connection,
                                RedisWorkerRegistryKeyspace keyspace,
                                long heartbeatFreshnessMillis,
                                boolean ownsClient) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.keyspace = keyspace != null ? keyspace : new RedisWorkerRegistryKeyspace();
        this.heartbeatFreshnessMillis = Math.max(1L, heartbeatFreshnessMillis);
        this.ownsClient = ownsClient;
    }

    @Override
    public synchronized void upsertSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling) {
        Objects.requireNonNull(meta, "meta");
        String workerId = meta.workerId();
        String groupId = meta.groupId();
        String previousGroupId = commands.hget(keyspace.workerGroupHash(), workerId);
        if (previousGroupId != null && !previousGroupId.equals(groupId)) {
            commands.zrem(keyspace.groupHeartbeatDeadlinesZset(previousGroupId), workerId);
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
                tx.sadd(keyspace.workerGroupsSet(), groupId);
                tx.hset(keyspace.workerGroupHash(), workerId, groupId);
                tx.zadd(keyspace.groupHeartbeatDeadlinesZset(groupId),
                        heartbeatDeadlineMillis(meta),
                        workerId);
            });
        });
    }

    @Override
    public synchronized boolean markSlotRemoving(String groupId, String workerId, String reason) {
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
        return result.changed();
    }

    @Override
    public synchronized CleanupSummary cleanupRemovedSlots(String groupId, int limit) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null || limit <= 0) {
            return CleanupSummary.empty();
        }
        int scanned = 0;
        int removed = 0;
        int skipped = 0;
        for (String workerId : scanHashFields(keyspace.groupSlotsHash(normalizedGroupId), limit)) {
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
            commands.zrem(keyspace.groupHeartbeatDeadlinesZset(normalizedGroupId), workerId);
            commands.srem(keyspace.exclusiveLeasesSet(), workerId);
            removed++;
        }
        return new CleanupSummary(scanned, removed, skipped);
    }

    @Override
    public synchronized Optional<WorkerSlot> slot(String groupId, String workerId) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(decodeSlot(commands.hget(keyspace.groupSlotsHash(normalizedGroupId), normalizedWorkerId)));
    }

    @Override
    public synchronized Optional<WorkerSlot> slotByWorkerId(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return Optional.empty();
        }
        String groupId = commands.hget(keyspace.workerGroupHash(), normalizedWorkerId);
        return groupId == null ? Optional.empty() : slot(groupId, normalizedWorkerId);
    }

    @Override
    public synchronized Set<String> workerIdsByGroupId(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return Set.of();
        }
        return Set.copyOf(commands.hkeys(keyspace.groupSlotsHash(normalizedGroupId)));
    }

    @Override
    public synchronized ReserveStatus slotLifecycleStatus(String groupId, String workerId, long nowMillis) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return ReserveStatus.MISSING_SLOT;
        }
        Optional<WorkerSlot> slot = slot(normalizedGroupId, normalizedWorkerId);
        if (slot.isEmpty()) {
            return slotByWorkerId(normalizedWorkerId).isPresent()
                    ? ReserveStatus.GROUP_MISMATCH
                    : ReserveStatus.MISSING_SLOT;
        }
        return validateSlotLifecycle(slot.orElseThrow(), normalizedGroupId, normalizedWorkerId, nowMillis);
    }

    @Override
    public synchronized boolean tryAcquireExclusiveLease(String groupId, String workerId) {
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
    public synchronized void releaseExclusiveLease(String groupId, String workerId) {
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
    public synchronized boolean hasExclusiveLease(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        return normalizedWorkerId != null && Boolean.TRUE.equals(commands.sismember(keyspace.exclusiveLeasesSet(), normalizedWorkerId));
    }

    @Override
    public synchronized List<String> exclusiveLeaseWorkerIds() {
        List<String> workerIds = new ArrayList<>(commands.smembers(keyspace.exclusiveLeasesSet()));
        workerIds.sort(String::compareTo);
        return List.copyOf(workerIds);
    }

    @Override
    public synchronized boolean disableDispatch(String groupId, String workerId, DispatchAvailabilitySource source) {
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
        boolean changed = Boolean.TRUE.equals(result.payload());
        return changed;
    }

    @Override
    public synchronized boolean blockDispatch(String groupId, String workerId, WorkerDispatchBlockRecord record) {
        Objects.requireNonNull(record, "record");
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null || slot(normalizedGroupId, normalizedWorkerId).isEmpty()) {
            return false;
        }
        Optional<WorkerDispatchBlockRecord> currentRecord = dispatchBlockRecord(
                normalizedGroupId,
                normalizedWorkerId,
                record.source()
        );
        if (currentRecord.isPresent() && record.observedAtMillis() < currentRecord.orElseThrow().observedAtMillis()) {
            return false;
        }
        MutationResult result = updateSlot(normalizedGroupId, normalizedWorkerId, current -> {
            if (current == null) {
                return SlotUpdate.noop(null, false);
            }
            EnumSet<DispatchAvailabilitySource> sources = disabledSources(current);
            boolean changed = sources.add(record.source());
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
            return SlotUpdate.write(updated,
                    tx -> tx.hset(
                            keyspace.groupDispatchBlocksHash(normalizedGroupId),
                            dispatchBlockField(normalizedWorkerId, record.source()),
                            encodeDispatchBlockRecord(record)
                    ),
                    true);
        });
        return Boolean.TRUE.equals(result.payload());
    }

    @Override
    public synchronized Optional<WorkerDispatchBlockRecord> dispatchBlockRecord(String groupId,
                                                                                String workerId,
                                                                                DispatchAvailabilitySource source) {
        Objects.requireNonNull(source, "source");
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(decodeDispatchBlockRecord(commands.hget(
                keyspace.groupDispatchBlocksHash(normalizedGroupId),
                dispatchBlockField(normalizedWorkerId, source)
        )));
    }

    @Override
    public synchronized boolean clearDispatchDisable(String groupId, String workerId, DispatchAvailabilitySource source) {
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
    public synchronized CleanupSummary cleanupExpiredHeartbeats(long nowMillis, int limit) {
        if (limit <= 0) {
            return CleanupSummary.empty();
        }
        int scanned = 0;
        int removed = 0;
        int skipped = 0;
        List<String> groupIds = new ArrayList<>(commands.smembers(keyspace.workerGroupsSet()));
        groupIds.sort(String::compareTo);
        for (String groupId : groupIds) {
            int remaining = limit - scanned;
            if (remaining <= 0) {
                return new CleanupSummary(scanned, removed, skipped);
            }
            List<String> workerIds = commands.zrangebyscore(
                    keyspace.groupHeartbeatDeadlinesZset(groupId),
                    Range.create(Double.NEGATIVE_INFINITY, (double) nowMillis),
                    Limit.create(0, remaining)
            );
            for (String workerId : workerIds) {
                scanned++;
                boolean changed = markSlotRemoving(groupId, workerId, "heartbeat expired");
                commands.zrem(keyspace.groupHeartbeatDeadlinesZset(groupId), workerId);
                if (changed) {
                    removed++;
                } else {
                    skipped++;
                }
            }
        }
        return new CleanupSummary(scanned, removed, skipped);
    }

    @Override
    public synchronized void close() {
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

    private WorkerDispatchBlockRecord decodeDispatchBlockRecord(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        DispatchBlockPayload payload = GSON.fromJson(json, DispatchBlockPayload.class);
        return payload == null ? null : payload.toRecord();
    }

    private String encodeDispatchBlockRecord(WorkerDispatchBlockRecord record) {
        return GSON.toJson(DispatchBlockPayload.from(record));
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

    private ReserveStatus validateSlotLifecycle(WorkerSlot current,
                                                String groupId,
                                                String workerId,
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
        return ReserveStatus.ACCEPTED;
    }

    private List<String> scanHashFields(String key, int limit) {
        if (key == null || limit <= 0) {
            return List.of();
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.limit(limit);
        while (fields.size() < limit) {
            MapScanCursor<String, String> page = cursor == ScanCursor.INITIAL
                    ? commands.hscan(key, args)
                    : commands.hscan(key, cursor, args);
            fields.addAll(page.getMap().keySet());
            cursor = page;
            if (cursor.isFinished()) {
                break;
            }
        }
        return fields.stream().limit(limit).toList();
    }

    private long heartbeatDeadlineMillis(WorkerMeta meta) {
        long lastHeartbeatMillis = Math.max(0L, meta.lastHeartbeatMillis());
        long maxIncrement = Long.MAX_VALUE - lastHeartbeatMillis;
        return lastHeartbeatMillis + Math.min(heartbeatFreshnessMillis, maxIncrement);
    }

    private static EnumSet<DispatchAvailabilitySource> disabledSources(WorkerSlot slot) {
        return slot.disabledSources().isEmpty()
                ? EnumSet.noneOf(DispatchAvailabilitySource.class)
                : EnumSet.copyOf(slot.disabledSources());
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String dispatchBlockField(String workerId, DispatchAvailabilitySource source) {
        return normalizeNullable(workerId) + ":" + Objects.requireNonNull(source, "source").name();
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

    private static final class DispatchBlockPayload {
        String source;
        String reason;
        long observedAtMillis;
        long suggestedRecheckAfterMillis;

        static DispatchBlockPayload from(WorkerDispatchBlockRecord record) {
            DispatchBlockPayload payload = new DispatchBlockPayload();
            payload.source = record.source().name();
            payload.reason = record.reason();
            payload.observedAtMillis = record.observedAtMillis();
            payload.suggestedRecheckAfterMillis = record.suggestedRecheckAfterMillis();
            return payload;
        }

        WorkerDispatchBlockRecord toRecord() {
            return new WorkerDispatchBlockRecord(
                    DispatchAvailabilitySource.valueOf(source),
                    reason,
                    observedAtMillis,
                    suggestedRecheckAfterMillis
            );
        }
    }

    private static final class WorkerMetaPayload {
        String workerId;
        String groupId;
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
