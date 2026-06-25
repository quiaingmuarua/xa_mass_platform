package com.xa.mass.runtime.worker;


import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Low-level runtime worker registry contract for worker slot metadata,
 * lifecycle gates, exclusive leases, and cleanup.
 *
 * <p>Score-band slot runtime owns production worker acquisition. This registry
 * must not grow a second candidate acquisition, reservation, or task-active
 * accounting owner. Worker-id semantic methods are support surfaces for
 * diagnostics, commands, and paths that genuinely lack group evidence; they
 * must not leak Redis physical key shape into engine or worker-runtime
 * callers.</p>
 */
public interface WorkerRegistry {

    void upsertSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling);

    boolean markSlotRemoving(String groupId, String workerId, String reason);

    default boolean markWorkerRemoving(String workerId, String reason) {
        return slotByWorkerId(workerId)
                .map(slot -> markSlotRemoving(slot.groupId(), slot.workerId(), reason))
                .orElse(false);
    }

    CleanupSummary cleanupRemovedSlots(String groupId, int limit);

    Optional<WorkerSlot> slot(String groupId, String workerId);

    Optional<WorkerSlot> slotByWorkerId(String workerId);

    default Optional<WorkerMeta> workerMeta(String workerId) {
        return slotByWorkerId(workerId).map(WorkerSlot::meta);
    }

    default Optional<WorkerAdmissionSnapshot> workerAdmissionSnapshot(String workerId) {
        return slotByWorkerId(workerId)
                .map(slot -> new WorkerAdmissionSnapshot(
                        slot.workerId(),
                        slot.activeLeaseCount(),
                        slot.reservedCount(),
                        slot.declaredCapacity()
                ));
    }

    Set<String> workerIdsByGroupId(String groupId);

    default int markWorkersRemovingByGroup(String groupId, String reason) {
        int changed = 0;
        for (String workerId : workerIdsByGroupId(groupId)) {
            if (markWorkerRemoving(workerId, reason)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Registry-owned slot lifecycle predicate for support callers.
     *
     * <p>This covers slot existence, group membership, removing state, heartbeat
     * freshness, and dispatch gate. Production worker acquisition uses
     * WorkerScoreBandSlotRuntime and must not use this method as a separate
     * candidate-source owner.</p>
     */
    ReserveStatus slotLifecycleStatus(String groupId, String workerId, long nowMillis);

    default boolean isSlotLifecycleEligible(String groupId, String workerId, long nowMillis) {
        return slotLifecycleStatus(groupId, workerId, nowMillis) == ReserveStatus.ACCEPTED;
    }

    boolean tryAcquireExclusiveLease(String groupId, String workerId);

    default boolean tryAcquireExclusiveLease(String workerId) {
        return slotByWorkerId(workerId)
                .map(slot -> tryAcquireExclusiveLease(slot.groupId(), slot.workerId()))
                .orElse(false);
    }

    void releaseExclusiveLease(String groupId, String workerId);

    default void releaseExclusiveLease(String workerId) {
        slotByWorkerId(workerId)
                .ifPresent(slot -> releaseExclusiveLease(slot.groupId(), slot.workerId()));
    }

    boolean hasExclusiveLease(String workerId);

    List<String> exclusiveLeaseWorkerIds();

    boolean disableDispatch(String groupId, String workerId, DispatchAvailabilitySource source);

    default boolean blockDispatch(String groupId, String workerId, WorkerDispatchBlockRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        return disableDispatch(groupId, workerId, record.source());
    }

    default boolean disableDispatch(String workerId, DispatchAvailabilitySource source) {
        return slotByWorkerId(workerId)
                .map(slot -> disableDispatch(slot.groupId(), slot.workerId(), source))
                .orElse(false);
    }

    default boolean blockDispatch(String workerId, WorkerDispatchBlockRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        return slotByWorkerId(workerId)
                .map(slot -> blockDispatch(slot.groupId(), slot.workerId(), record))
                .orElse(false);
    }

    default Optional<WorkerDispatchBlockRecord> dispatchBlockRecord(String groupId,
                                                                    String workerId,
                                                                    DispatchAvailabilitySource source) {
        return Optional.empty();
    }

    default Optional<WorkerDispatchBlockRecord> dispatchBlockRecord(String workerId,
                                                                    DispatchAvailabilitySource source) {
        return slotByWorkerId(workerId)
                .flatMap(slot -> dispatchBlockRecord(slot.groupId(), slot.workerId(), source));
    }

    boolean clearDispatchDisable(String groupId, String workerId, DispatchAvailabilitySource source);

    default boolean clearDispatchDisable(String workerId, DispatchAvailabilitySource source) {
        return slotByWorkerId(workerId)
                .map(slot -> clearDispatchDisable(slot.groupId(), slot.workerId(), source))
                .orElse(false);
    }

    /**
     * Clears a dispatch-disable source only when the worker slot is still
     * present, not removing, and currently blocked by that source.
     */
    default boolean recoverDispatchDisable(String workerId, DispatchAvailabilitySource source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        return slotByWorkerId(workerId)
                .filter(slot -> !slot.removing())
                .filter(slot -> slot.disabledSources().contains(source))
                .map(slot -> clearDispatchDisable(slot.groupId(), slot.workerId(), source))
                .orElse(false);
    }

    default boolean isDispatchEnabled(String workerId) {
        return slotByWorkerId(workerId)
                .map(slot -> !slot.removing() && slot.dispatchEnabled())
                .orElse(false);
    }

    CleanupSummary cleanupExpiredHeartbeats(long nowMillis, int limit);
}
