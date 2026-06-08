package com.xa.mass.runtime.memory;

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
import com.xa.mass.runtime.worker.DefaultWorkerCandidateBucketPolicy;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import com.xa.mass.runtime.worker.WorkerSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Group-partitioned in-memory WorkerRegistry implementation.
 */
public final class InMemoryWorkerRegistry implements WorkerRegistry {

    public static final long DEFAULT_HEARTBEAT_FRESHNESS_MILLIS = 30_000L;

    private final WorkerCandidateBucketPolicy candidateBucketPolicy;
    private final WorkerCandidateSamplingPolicy samplingPolicy;
    private final long heartbeatFreshnessMillis;
    private final ConcurrentMap<String, ConcurrentMap<String, AtomicReference<WorkerSlot>>> slotsByGroupId =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> workerIdToGroupId = new ConcurrentHashMap<>();
    private final ConcurrentMap<GroupCandidateBucketKey, Set<String>> candidateBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<NodeGroupCandidateBucketKey, Set<String>> nodeCandidateBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BucketMembership> bucketMembershipByWorkerId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> taskActiveWorkersByTask = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Integer>> taskWorkerActiveCounts = new ConcurrentHashMap<>();

    public InMemoryWorkerRegistry() {
        this(DefaultWorkerCandidateBucketPolicy.defaultPolicy(), RandomWorkerCandidateSamplingPolicy.defaultPolicy());
    }

    public InMemoryWorkerRegistry(WorkerCandidateSamplingPolicy samplingPolicy) {
        this(DefaultWorkerCandidateBucketPolicy.defaultPolicy(), samplingPolicy);
    }

    public InMemoryWorkerRegistry(WorkerCandidateBucketPolicy candidateBucketPolicy, WorkerCandidateSamplingPolicy samplingPolicy) {
        this(candidateBucketPolicy, samplingPolicy, DEFAULT_HEARTBEAT_FRESHNESS_MILLIS);
    }

    public InMemoryWorkerRegistry(WorkerCandidateBucketPolicy candidateBucketPolicy,
                                  WorkerCandidateSamplingPolicy samplingPolicy,
                                  long heartbeatFreshnessMillis) {
        this.candidateBucketPolicy = candidateBucketPolicy != null ? candidateBucketPolicy : DefaultWorkerCandidateBucketPolicy.defaultPolicy();
        this.samplingPolicy = samplingPolicy != null
                ? samplingPolicy
                : RandomWorkerCandidateSamplingPolicy.defaultPolicy();
        this.heartbeatFreshnessMillis = Math.max(1L, heartbeatFreshnessMillis);
    }

    @Override
    public void upsertSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling) {
        Objects.requireNonNull(meta, "meta");
        String workerId = meta.workerId();
        String groupId = meta.groupId();
        String previousGroupId = workerIdToGroupId.put(workerId, groupId);
        if (previousGroupId != null && !previousGroupId.equals(groupId)) {
            removeFromBuckets(previousGroupId, workerId);
            ConcurrentMap<String, AtomicReference<WorkerSlot>> previousGroup = slotsByGroupId.get(previousGroupId);
            if (previousGroup != null) {
                previousGroup.remove(workerId);
            }
        }

        ConcurrentMap<String, AtomicReference<WorkerSlot>> groupSlots =
                slotsByGroupId.computeIfAbsent(groupId, ignored -> new ConcurrentHashMap<>());
        AtomicReference<WorkerSlot> slotRef = groupSlots.computeIfAbsent(
                workerId,
                ignored -> new AtomicReference<>(newSlot(meta, declaredCapacity, eventBindingCeiling))
        );
        update(slotRef, current -> {
            if (current == null) {
                return newSlot(meta, declaredCapacity, eventBindingCeiling);
            }
            return new WorkerSlot(
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
        });
        removeFromBuckets(groupId, workerId);
        addToBuckets(meta);
    }

    @Override
    public boolean markSlotRemoving(String groupId, String workerId, String reason) {
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return false;
        }
        boolean[] changed = new boolean[1];
        update(slotRef.orElseThrow(), current -> {
            if (current == null || current.removing()) {
                return current;
            }
            changed[0] = true;
            return new WorkerSlot(
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
            );
        });
        removeFromBuckets(normalizeNullable(groupId), normalizeNullable(workerId));
        return changed[0];
    }

    @Override
    public CleanupSummary cleanupRemovedSlots(String groupId, int limit) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null || limit <= 0) {
            return CleanupSummary.empty();
        }
        ConcurrentMap<String, AtomicReference<WorkerSlot>> groupSlots = slotsByGroupId.get(normalizedGroupId);
        if (groupSlots == null || groupSlots.isEmpty()) {
            return CleanupSummary.empty();
        }

        int scanned = 0;
        int removed = 0;
        int skipped = 0;
        for (Map.Entry<String, AtomicReference<WorkerSlot>> entry : groupSlots.entrySet()) {
            if (scanned >= limit) {
                break;
            }
            scanned++;
            WorkerSlot current = entry.getValue().get();
            if (current == null || !current.removing() || current.occupiedPermits() > 0) {
                skipped++;
                continue;
            }
            if (groupSlots.remove(entry.getKey(), entry.getValue())) {
                workerIdToGroupId.remove(entry.getKey(), normalizedGroupId);
                removeFromBuckets(normalizedGroupId, entry.getKey());
                removed++;
            } else {
                skipped++;
            }
        }
        return new CleanupSummary(scanned, removed, skipped);
    }

    @Override
    public Optional<WorkerSlot> slot(String groupId, String workerId) {
        return slotRef(groupId, workerId).map(AtomicReference::get);
    }

    @Override
    public Optional<WorkerSlot> slotByWorkerId(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return Optional.empty();
        }
        String groupId = workerIdToGroupId.get(normalizedWorkerId);
        return groupId == null ? Optional.empty() : slot(groupId, normalizedWorkerId);
    }

    @Override
    public Set<String> workerIdsByGroupId(String groupId) {
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedGroupId == null) {
            return Set.of();
        }
        ConcurrentMap<String, AtomicReference<WorkerSlot>> groupSlots = slotsByGroupId.get(normalizedGroupId);
        return groupSlots == null ? Set.of() : Set.copyOf(groupSlots.keySet());
    }

    @Override
    public Set<String> workerIdsByAdapterNodeGroup(String adapterNodeId, String groupId) {
        String normalizedAdapterNodeId = normalizeNullable(adapterNodeId);
        String normalizedGroupId = normalizeNullable(groupId);
        if (normalizedAdapterNodeId == null || normalizedGroupId == null) {
            return Set.of();
        }
        ConcurrentMap<String, AtomicReference<WorkerSlot>> groupSlots = slotsByGroupId.get(normalizedGroupId);
        if (groupSlots == null || groupSlots.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (AtomicReference<WorkerSlot> slotRef : groupSlots.values()) {
            WorkerSlot slot = slotRef.get();
            if (slot != null && normalizedAdapterNodeId.equals(slot.adapterNodeId())) {
                workerIds.add(slot.workerId());
            }
        }
        return Set.copyOf(workerIds);
    }

    @Override
    public List<String> acquireCandidates(String groupId, String candidateBucketKey, int maxCandidateCount) {
        return acquireCandidates(groupId, null, candidateBucketKey, maxCandidateCount);
    }

    @Override
    public List<String> acquireCandidates(String groupId,
                                          String adapterNodeId,
                                          String candidateBucketKey,
                                          int maxCandidateCount) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedCandidateBucketKey = normalizeCandidateBucketKey(candidateBucketKey);
        String normalizedAdapterNodeId = normalizeNullable(adapterNodeId);
        if (normalizedGroupId == null || normalizedCandidateBucketKey == null || maxCandidateCount <= 0) {
            return List.of();
        }

        Set<String> workerIds = normalizedAdapterNodeId == null
                ? candidateBuckets.getOrDefault(new GroupCandidateBucketKey(normalizedGroupId, normalizedCandidateBucketKey), Set.of())
                : nodeCandidateBuckets.getOrDefault(
                        new NodeGroupCandidateBucketKey(normalizedGroupId, normalizedAdapterNodeId, normalizedCandidateBucketKey),
                        Set.of()
                );
        return samplingPolicy.sample(
                new WorkerCandidateSamplingContext(normalizedGroupId, normalizedAdapterNodeId, normalizedCandidateBucketKey),
                snapshotWorkerIds(workerIds),
                maxCandidateCount
        );
    }

    @Override
    public ReserveResult tryReserve(String groupId, String workerId, String taskId, int permits, long nowMillis) {
        int normalizedPermits = Math.max(1, permits);
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            if (slotByWorkerId(workerId).isPresent()) {
                return ReserveResult.rejected(ReserveStatus.GROUP_MISMATCH, "worker group mismatch");
            }
            return ReserveResult.rejected(ReserveStatus.MISSING_SLOT, "worker slot missing");
        }

        AtomicReference<WorkerSlot> ref = slotRef.orElseThrow();
        while (true) {
            WorkerSlot current = ref.get();
            ReserveStatus status = validateReserve(current, groupId, workerId, normalizedPermits, nowMillis);
            if (status != ReserveStatus.ACCEPTED) {
                return ReserveResult.rejected(status, status.name());
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
            if (ref.compareAndSet(current, updated)) {
                return ReserveResult.accepted(updated);
            }
        }
    }

    @Override
    public boolean confirmReservation(String groupId, String workerId, String taskId, int permits) {
        int normalizedPermits = Math.max(1, permits);
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return false;
        }
        AtomicReference<WorkerSlot> ref = slotRef.orElseThrow();
        while (true) {
            WorkerSlot current = ref.get();
            if (current == null || current.removing() || current.reservedCount() < normalizedPermits) {
                return false;
            }
            Map<String, Integer> activeByTask = incrementTaskCount(current.activeLeaseCountByTask(),
                    taskId,
                    normalizedPermits);
            WorkerSlot updated = new WorkerSlot(
                    current.meta(),
                    current.declaredCapacity(),
                    current.eventBindingCeiling(),
                    current.activeLeaseCount() + normalizedPermits,
                    current.reservedCount() - normalizedPermits,
                    activeByTask,
                    current.disabledSources(),
                    current.exclusiveLeaseHeld(),
                    current.removing(),
                    current.removingReason()
            );
            if (ref.compareAndSet(current, updated)) {
                incrementTaskProjection(taskId, workerId, normalizedPermits);
                return true;
            }
        }
    }

    @Override
    public void releaseReservation(String groupId, String workerId, String taskId, int permits) {
        int normalizedPermits = Math.max(1, permits);
        slotRef(groupId, workerId).ifPresent(ref -> update(ref, current -> {
            if (current == null) {
                return null;
            }
            return new WorkerSlot(
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
            );
        }));
    }

    @Override
    public void recordWorkClaimed(String groupId, String workerId, String taskId, int permits) {
        int normalizedPermits = Math.max(1, permits);
        slotRef(groupId, workerId).ifPresent(ref -> {
            boolean[] changed = new boolean[1];
            update(ref, current -> {
                if (current == null) {
                    return null;
                }
                changed[0] = true;
                return new WorkerSlot(
                        current.meta(),
                        current.declaredCapacity(),
                        current.eventBindingCeiling(),
                        current.activeLeaseCount() + normalizedPermits,
                        current.reservedCount(),
                        incrementTaskCount(current.activeLeaseCountByTask(), taskId, normalizedPermits),
                        current.disabledSources(),
                        current.exclusiveLeaseHeld(),
                        current.removing(),
                        current.removingReason()
                );
            });
            if (changed[0]) {
                incrementTaskProjection(taskId, workerId, normalizedPermits);
            }
        });
    }

    @Override
    public void recordWorkFinal(String groupId, String workerId, String taskId, int permits) {
        int normalizedPermits = Math.max(1, permits);
        slotRef(groupId, workerId).ifPresent(ref -> {
            boolean[] changed = new boolean[1];
            update(ref, current -> {
                if (current == null) {
                    return null;
                }
                int released = Math.min(normalizedPermits, current.activeLeaseCount());
                if (released <= 0) {
                    return current;
                }
                changed[0] = true;
                return new WorkerSlot(
                        current.meta(),
                        current.declaredCapacity(),
                        current.eventBindingCeiling(),
                        Math.max(0, current.activeLeaseCount() - released),
                        current.reservedCount(),
                        decrementTaskCount(current.activeLeaseCountByTask(), taskId, released),
                        current.disabledSources(),
                        current.exclusiveLeaseHeld(),
                        current.removing(),
                        current.removingReason()
                );
            });
            if (changed[0]) {
                decrementTaskProjection(taskId, workerId, normalizedPermits);
            }
        });
    }

    @Override
    public boolean tryAcquireExclusiveLease(String groupId, String workerId) {
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return false;
        }
        boolean[] acquired = new boolean[1];
        update(slotRef.orElseThrow(), current -> {
            if (current == null || current.removing() || current.exclusiveLeaseHeld()) {
                return current;
            }
            acquired[0] = true;
            return new WorkerSlot(
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
        });
        return acquired[0];
    }

    @Override
    public void releaseExclusiveLease(String groupId, String workerId) {
        slotRef(groupId, workerId).ifPresent(ref -> update(ref, current -> {
            if (current == null || !current.exclusiveLeaseHeld()) {
                return current;
            }
            return new WorkerSlot(
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
        }));
    }

    @Override
    public boolean hasExclusiveLease(String workerId) {
        return slotByWorkerId(workerId)
                .map(WorkerSlot::exclusiveLeaseHeld)
                .orElse(false);
    }

    @Override
    public List<String> exclusiveLeaseWorkerIds() {
        List<String> workerIds = new ArrayList<>();
        for (ConcurrentMap<String, AtomicReference<WorkerSlot>> groupSlots : slotsByGroupId.values()) {
            for (Map.Entry<String, AtomicReference<WorkerSlot>> entry : groupSlots.entrySet()) {
                WorkerSlot slot = entry.getValue().get();
                if (slot != null && slot.exclusiveLeaseHeld()) {
                    workerIds.add(entry.getKey());
                }
            }
        }
        return List.copyOf(workerIds);
    }

    @Override
    public boolean disableDispatch(String groupId, String workerId, DispatchAvailabilitySource source) {
        Objects.requireNonNull(source, "source");
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return false;
        }
        boolean[] changed = new boolean[1];
        update(slotRef.orElseThrow(), current -> {
            if (current == null) {
                return null;
            }
            EnumSet<DispatchAvailabilitySource> sources = disabledSources(current);
            changed[0] = sources.add(source);
            return new WorkerSlot(
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
        });
        return changed[0];
    }

    @Override
    public boolean clearDispatchDisable(String groupId, String workerId, DispatchAvailabilitySource source) {
        Objects.requireNonNull(source, "source");
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return false;
        }
        boolean[] changed = new boolean[1];
        update(slotRef.orElseThrow(), current -> {
            if (current == null) {
                return null;
            }
            EnumSet<DispatchAvailabilitySource> sources = disabledSources(current);
            changed[0] = sources.remove(source);
            return new WorkerSlot(
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
        });
        return changed[0];
    }

    @Override
    public Set<String> activeWorkerIdsByTask(String taskId) {
        String normalizedTaskId = normalizeNullable(taskId);
        if (normalizedTaskId == null) {
            return Set.of();
        }
        return Set.copyOf(taskActiveWorkersByTask.getOrDefault(normalizedTaskId, Set.of()));
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
        return taskWorkerActiveCounts
                .getOrDefault(normalizedTaskId, new ConcurrentHashMap<>())
                .getOrDefault(normalizedWorkerId, 0);
    }

    @Override
    public void markCandidateStale(String groupId, String workerId, String reason) {
        removeFromBuckets(normalizeNullable(groupId), normalizeNullable(workerId));
    }

    @Override
    public CleanupSummary cleanupExpiredHeartbeats(long nowMillis, int limit) {
        int scanned = 0;
        int removed = 0;
        int skipped = 0;
        for (Map.Entry<String, ConcurrentMap<String, AtomicReference<WorkerSlot>>> groupEntry : slotsByGroupId.entrySet()) {
            for (Map.Entry<String, AtomicReference<WorkerSlot>> slotEntry : groupEntry.getValue().entrySet()) {
                if (scanned >= limit) {
                    return new CleanupSummary(scanned, removed, skipped);
                }
                scanned++;
                WorkerSlot slot = slotEntry.getValue().get();
                if (slot == null || heartbeatDeadlineMillis(slot.meta()) > nowMillis) {
                    skipped++;
                    continue;
                }
                if (markSlotRemoving(groupEntry.getKey(), slotEntry.getKey(), "heartbeat expired")) {
                    removed++;
                } else {
                    skipped++;
                }
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
        for (Map.Entry<GroupCandidateBucketKey, Set<String>> entry : candidateBuckets.entrySet()) {
            if (!normalizedGroupId.equals(entry.getKey().groupId())) {
                continue;
            }
            List<String> snapshot = snapshotWorkerIds(entry.getValue());
            for (String workerId : snapshot) {
                if (scanned >= limit) {
                    return new CleanupSummary(scanned, removed, 0);
                }
                scanned++;
                if (slot(normalizedGroupId, workerId).isEmpty()) {
                    entry.getValue().remove(workerId);
                    removed++;
                }
            }
        }
        return new CleanupSummary(scanned, removed, 0);
    }

    private Optional<AtomicReference<WorkerSlot>> slotRef(String groupId, String workerId) {
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        ConcurrentMap<String, AtomicReference<WorkerSlot>> groupSlots = slotsByGroupId.get(normalizedGroupId);
        return groupSlots == null ? Optional.empty() : Optional.ofNullable(groupSlots.get(normalizedWorkerId));
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
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (!current.groupId().equals(normalizedGroupId) || !current.workerId().equals(normalizedWorkerId)) {
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
        LinkedHashSet<GroupCandidateBucketKey> groupKeys = new LinkedHashSet<>();
        LinkedHashSet<NodeGroupCandidateBucketKey> nodeKeys = new LinkedHashSet<>();
        for (String candidateBucketKey : candidateBucketKeys(meta)) {
            GroupCandidateBucketKey groupKey = new GroupCandidateBucketKey(meta.groupId(), candidateBucketKey);
            candidateBuckets.computeIfAbsent(
                    groupKey,
                    ignored -> newWorkerBucketSet()
            ).add(meta.workerId());
            groupKeys.add(groupKey);
            if (meta.adapterNodeId() != null) {
                NodeGroupCandidateBucketKey nodeKey =
                        new NodeGroupCandidateBucketKey(meta.groupId(), meta.adapterNodeId(), candidateBucketKey);
                nodeCandidateBuckets.computeIfAbsent(
                        nodeKey,
                        ignored -> newWorkerBucketSet()
                ).add(meta.workerId());
                nodeKeys.add(nodeKey);
            }
        }
        bucketMembershipByWorkerId.put(meta.workerId(), new BucketMembership(groupKeys, nodeKeys));
    }

    private void removeFromBuckets(String groupId, String workerId) {
        if (groupId == null || workerId == null) {
            return;
        }
        BucketMembership membership = bucketMembershipByWorkerId.remove(workerId);
        if (membership == null) {
            return;
        }
        for (GroupCandidateBucketKey key : membership.groupKeys()) {
            if (!groupId.equals(key.groupId())) {
                continue;
            }
            Set<String> workers = candidateBuckets.get(key);
            if (workers != null) {
                workers.remove(workerId);
            }
        }
        for (NodeGroupCandidateBucketKey key : membership.nodeKeys()) {
            if (!groupId.equals(key.groupId())) {
                continue;
            }
            Set<String> workers = nodeCandidateBuckets.get(key);
            if (workers != null) {
                workers.remove(workerId);
            }
        }
    }

    private Set<String> candidateBucketKeys(WorkerMeta meta) {
        Set<String> candidateBucketKeys = candidateBucketPolicy.candidateBucketKeysForWorkerMeta(meta);
        return candidateBucketKeys == null || candidateBucketKeys.isEmpty()
                ? Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY)
                : Set.copyOf(candidateBucketKeys);
    }

    private long heartbeatDeadlineMillis(WorkerMeta meta) {
        long lastHeartbeatMillis = Math.max(0L, meta.lastHeartbeatMillis());
        long maxIncrement = Long.MAX_VALUE - lastHeartbeatMillis;
        return lastHeartbeatMillis + Math.min(heartbeatFreshnessMillis, maxIncrement);
    }

    private static Set<String> newWorkerBucketSet() {
        return Collections.synchronizedSet(new LinkedHashSet<>());
    }

    private static List<String> snapshotWorkerIds(Set<String> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) {
            return List.of();
        }
        synchronized (workerIds) {
            return List.copyOf(workerIds);
        }
    }

    private static WorkerSlot update(AtomicReference<WorkerSlot> ref, SlotUpdater updater) {
        while (true) {
            WorkerSlot current = ref.get();
            WorkerSlot updated = updater.update(current);
            if (ref.compareAndSet(current, updated)) {
                return updated;
            }
        }
    }

    private static Map<String, Integer> incrementTaskCount(Map<String, Integer> current,
                                                           String taskId,
                                                           int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        if (normalizedTaskId == null) {
            return current;
        }
        ConcurrentHashMap<String, Integer> updated = new ConcurrentHashMap<>(current);
        updated.merge(normalizedTaskId, permits, Integer::sum);
        return Map.copyOf(updated);
    }

    private static Map<String, Integer> decrementTaskCount(Map<String, Integer> current,
                                                           String taskId,
                                                           int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        if (normalizedTaskId == null || current.isEmpty()) {
            return current;
        }
        ConcurrentHashMap<String, Integer> updated = new ConcurrentHashMap<>(current);
        updated.computeIfPresent(normalizedTaskId, (ignored, value) -> {
            int next = Math.max(0, value - permits);
            return next == 0 ? null : next;
        });
        return Map.copyOf(updated);
    }

    private void incrementTaskProjection(String taskId, String workerId, int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedTaskId == null || normalizedWorkerId == null) {
            return;
        }
        taskActiveWorkersByTask
                .computeIfAbsent(normalizedTaskId, ignored -> ConcurrentHashMap.newKeySet())
                .add(normalizedWorkerId);
        taskWorkerActiveCounts
                .computeIfAbsent(normalizedTaskId, ignored -> new ConcurrentHashMap<>())
                .merge(normalizedWorkerId, permits, Integer::sum);
    }

    private void decrementTaskProjection(String taskId, String workerId, int permits) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedTaskId == null || normalizedWorkerId == null) {
            return;
        }
        ConcurrentMap<String, Integer> workerCounts = taskWorkerActiveCounts.get(normalizedTaskId);
        if (workerCounts == null) {
            return;
        }
        workerCounts.computeIfPresent(normalizedWorkerId, (ignored, value) -> {
            int next = Math.max(0, value - permits);
            return next == 0 ? null : next;
        });
        if (workerCounts.isEmpty()) {
            taskWorkerActiveCounts.remove(normalizedTaskId, workerCounts);
        }
        if (!workerCounts.containsKey(normalizedWorkerId)) {
            taskActiveWorkersByTask.computeIfPresent(normalizedTaskId, (ignored, workers) -> {
                workers.remove(normalizedWorkerId);
                return workers.isEmpty() ? null : workers;
            });
        }
    }

    private static EnumSet<DispatchAvailabilitySource> disabledSources(WorkerSlot slot) {
        return slot.disabledSources().isEmpty()
                ? EnumSet.noneOf(DispatchAvailabilitySource.class)
                : EnumSet.copyOf(slot.disabledSources());
    }

    private static String normalizeCandidateBucketKey(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY : normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private interface SlotUpdater {
        WorkerSlot update(WorkerSlot current);
    }

    private record BucketMembership(Set<GroupCandidateBucketKey> groupKeys,
                                    Set<NodeGroupCandidateBucketKey> nodeKeys) {
        private BucketMembership {
            groupKeys = groupKeys == null || groupKeys.isEmpty() ? Set.of() : Set.copyOf(groupKeys);
            nodeKeys = nodeKeys == null || nodeKeys.isEmpty() ? Set.of() : Set.copyOf(nodeKeys);
        }
    }

    private record GroupCandidateBucketKey(String groupId, String candidateBucketKey) {
    }

    private record NodeGroupCandidateBucketKey(String groupId, String adapterNodeId, String candidateBucketKey) {
    }
}
