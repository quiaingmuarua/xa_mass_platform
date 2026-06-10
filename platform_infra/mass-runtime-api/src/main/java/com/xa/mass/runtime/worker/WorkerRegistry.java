package com.xa.mass.runtime.worker;


import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime worker registry contract for bounded candidate acquisition and
 * semantic worker-state operations.
 *
 * <p>Upper runtime owners should prefer worker-id semantic methods such as
 * {@link #workerMeta(String)}, {@link #tryReserve(String, String, int, long)},
 * and {@link #disableDispatch(String, DispatchAvailabilitySource)}. Slot and
 * group-scoped methods are retained for registry implementation contracts and
 * bounded maintenance paths; they must not leak Redis physical key shape into
 * engine or worker-runtime callers.</p>
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

    Set<String> workerIdsByAdapterNodeGroup(String adapterNodeId, String groupId);

    default int disableDispatchForAdapterNodeGroup(String adapterNodeId,
                                                   String groupId,
                                                   DispatchAvailabilitySource source) {
        int changed = 0;
        for (String workerId : workerIdsByAdapterNodeGroup(adapterNodeId, groupId)) {
            if (disableDispatch(workerId, source)) {
                changed++;
            }
        }
        return changed;
    }

    default int clearDispatchDisableForAdapterNodeGroup(String adapterNodeId,
                                                        String groupId,
                                                        DispatchAvailabilitySource source) {
        int changed = 0;
        for (String workerId : workerIdsByAdapterNodeGroup(adapterNodeId, groupId)) {
            if (clearDispatchDisable(workerId, source)) {
                changed++;
            }
        }
        return changed;
    }

    List<String> acquireCandidates(String groupId, String candidateBucketKey, int maxCandidateCount);

    List<String> acquireCandidates(String groupId,
                                   String adapterNodeId,
                                   String candidateBucketKey,
                                   int maxCandidateCount);

    ReserveResult tryReserve(String groupId, String workerId, String taskId, int permits, long nowMillis);

    default ReserveResult tryReserve(String workerId, String taskId, int permits, long nowMillis) {
        return slotByWorkerId(workerId)
                .map(slot -> tryReserve(slot.groupId(), slot.workerId(), taskId, permits, nowMillis))
                .orElseGet(() -> ReserveResult.rejected(ReserveStatus.MISSING_SLOT, "worker slot missing"));
    }

    boolean confirmReservation(String groupId, String workerId, String taskId, int permits);

    default boolean confirmReservation(String workerId, String taskId, int permits) {
        return slotByWorkerId(workerId)
                .map(slot -> confirmReservation(slot.groupId(), slot.workerId(), taskId, permits))
                .orElse(false);
    }

    void releaseReservation(String groupId, String workerId, String taskId, int permits);

    default void releaseReservation(String workerId, String taskId, int permits) {
        slotByWorkerId(workerId)
                .ifPresent(slot -> releaseReservation(slot.groupId(), slot.workerId(), taskId, permits));
    }

    void recordWorkClaimed(String groupId, String workerId, String taskId, int permits);

    default void recordWorkClaimed(String workerId, String taskId, int permits) {
        slotByWorkerId(workerId)
                .ifPresent(slot -> recordWorkClaimed(slot.groupId(), slot.workerId(), taskId, permits));
    }

    void recordWorkFinal(String groupId, String workerId, String taskId, int permits);

    default void recordWorkFinal(String workerId, String taskId, int permits) {
        slotByWorkerId(workerId)
                .ifPresent(slot -> recordWorkFinal(slot.groupId(), slot.workerId(), taskId, permits));
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

    default boolean disableDispatch(String workerId, DispatchAvailabilitySource source) {
        return slotByWorkerId(workerId)
                .map(slot -> disableDispatch(slot.groupId(), slot.workerId(), source))
                .orElse(false);
    }

    boolean clearDispatchDisable(String groupId, String workerId, DispatchAvailabilitySource source);

    default boolean clearDispatchDisable(String workerId, DispatchAvailabilitySource source) {
        return slotByWorkerId(workerId)
                .map(slot -> clearDispatchDisable(slot.groupId(), slot.workerId(), source))
                .orElse(false);
    }

    default boolean isDispatchEnabled(String workerId) {
        return slotByWorkerId(workerId)
                .map(slot -> !slot.removing() && slot.dispatchEnabled())
                .orElse(false);
    }

    Set<String> activeWorkerIdsByTask(String taskId);

    int activeWorkerCountForTask(String taskId);

    int activeLeaseCountByTaskWorker(String taskId, String workerId);

    void markCandidateStale(String groupId, String workerId, String reason);

    CleanupSummary cleanupExpiredHeartbeats(long nowMillis, int limit);

    CleanupSummary cleanupStaleBucketMembers(String groupId, int limit);
}
