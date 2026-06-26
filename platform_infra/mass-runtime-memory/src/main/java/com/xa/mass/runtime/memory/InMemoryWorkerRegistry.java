package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.worker.CleanupSummary;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.runtime.worker.WorkerSlot;

import java.util.ArrayList;
import java.util.EnumSet;
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

    private final long heartbeatFreshnessMillis;
    private final ConcurrentMap<String, ConcurrentMap<String, AtomicReference<WorkerSlot>>> slotsByGroupId =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> workerIdToGroupId = new ConcurrentHashMap<>();
    private final ConcurrentMap<DispatchBlockKey, WorkerDispatchBlockRecord> dispatchBlockRecords =
            new ConcurrentHashMap<>();

    public InMemoryWorkerRegistry() {
        this(DEFAULT_HEARTBEAT_FRESHNESS_MILLIS);
    }

    public InMemoryWorkerRegistry(long heartbeatFreshnessMillis) {
        this.heartbeatFreshnessMillis = Math.max(1L, heartbeatFreshnessMillis);
    }

    @Override
    public void upsertSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling) {
        Objects.requireNonNull(meta, "meta");
        String workerId = meta.workerId();
        String groupId = meta.groupId();
        String previousGroupId = workerIdToGroupId.put(workerId, groupId);
        if (previousGroupId != null && !previousGroupId.equals(groupId)) {
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
    public ReserveStatus slotLifecycleStatus(String groupId, String workerId, long nowMillis) {
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return slotByWorkerId(workerId).isPresent()
                    ? ReserveStatus.GROUP_MISMATCH
                    : ReserveStatus.MISSING_SLOT;
        }
        WorkerSlot current = slotRef.orElseThrow().get();
        return validateSlotLifecycle(current, groupId, workerId, nowMillis);
    }

    @Override
    public boolean tryAcquireExclusiveLease(String groupId, String workerId) {
        Optional<AtomicReference<WorkerSlot>> slotRef = slotRef(groupId, workerId);
        if (slotRef.isEmpty()) {
            return false;
        }
        AtomicReference<WorkerSlot> ref = slotRef.orElseThrow();
        while (true) {
            WorkerSlot current = ref.get();
            if (current == null || current.removing() || current.exclusiveLeaseHeld()) {
                return false;
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
            if (ref.compareAndSet(current, updated)) {
                return true;
            }
        }
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
    public boolean blockDispatch(String groupId, String workerId, WorkerDispatchBlockRecord record) {
        Objects.requireNonNull(record, "record");
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null || slot(normalizedGroupId, normalizedWorkerId).isEmpty()) {
            return false;
        }
        DispatchBlockKey key = new DispatchBlockKey(normalizedGroupId, normalizedWorkerId, record.source());
        AtomicReference<Boolean> accepted = new AtomicReference<>(Boolean.FALSE);
        dispatchBlockRecords.compute(key, (ignored, current) -> {
            if (current != null && record.observedAtMillis() < current.observedAtMillis()) {
                return current;
            }
            accepted.set(Boolean.TRUE);
            return record;
        });
        if (!accepted.get()) {
            return false;
        }
        disableDispatch(normalizedGroupId, normalizedWorkerId, record.source());
        return true;
    }

    @Override
    public Optional<WorkerDispatchBlockRecord> dispatchBlockRecord(String groupId,
                                                                   String workerId,
                                                                   DispatchAvailabilitySource source) {
        Objects.requireNonNull(source, "source");
        String normalizedGroupId = normalizeNullable(groupId);
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedGroupId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(dispatchBlockRecords.get(new DispatchBlockKey(
                normalizedGroupId,
                normalizedWorkerId,
                source
        )));
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

    private ReserveStatus validateSlotLifecycle(WorkerSlot current,
                                                String groupId,
                                                String workerId,
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
        return ReserveStatus.ACCEPTED;
    }

    private long heartbeatDeadlineMillis(WorkerMeta meta) {
        long lastHeartbeatMillis = Math.max(0L, meta.lastHeartbeatMillis());
        long maxIncrement = Long.MAX_VALUE - lastHeartbeatMillis;
        return lastHeartbeatMillis + Math.min(heartbeatFreshnessMillis, maxIncrement);
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

    private static EnumSet<DispatchAvailabilitySource> disabledSources(WorkerSlot slot) {
        return slot.disabledSources().isEmpty()
                ? EnumSet.noneOf(DispatchAvailabilitySource.class)
                : EnumSet.copyOf(slot.disabledSources());
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private interface SlotUpdater {
        WorkerSlot update(WorkerSlot current);
    }

    private record DispatchBlockKey(String groupId, String workerId, DispatchAvailabilitySource source) {
    }
}
