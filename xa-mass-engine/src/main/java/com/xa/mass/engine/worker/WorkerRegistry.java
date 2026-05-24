package com.xa.mass.engine.worker;

import com.xa.mass.engine.worker.WorkerDispatchAvailabilityOwner.DispatchAvailabilitySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime worker registry contract for bounded candidate acquisition and per-worker admission.
 */
public interface WorkerRegistry {

    void upsertSlot(WorkerMeta meta, int declaredCapacity, Set<EventKey> eventBindingCeiling);

    boolean markSlotRemoving(String groupId, String workerId, String reason);

    CleanupSummary cleanupRemovedSlots(String groupId, int limit);

    Optional<WorkerSlot> slot(String groupId, String workerId);

    Optional<WorkerSlot> slotByWorkerId(String workerId);

    List<String> acquireCandidates(String groupId, String routeBucketKey, int maxCandidateCount);

    List<String> acquireCandidates(String groupId,
                                   String adapterNodeId,
                                   String routeBucketKey,
                                   int maxCandidateCount);

    ReserveResult tryReserve(String groupId, String workerId, String taskId, int permits, long nowMillis);

    boolean confirmReservation(String groupId, String workerId, String taskId, int permits);

    void releaseReservation(String groupId, String workerId, String taskId, int permits);

    void recordWorkClaimed(String groupId, String workerId, String taskId, int permits);

    void recordWorkFinal(String groupId, String workerId, String taskId, int permits);

    boolean tryAcquireExclusiveLease(String groupId, String workerId);

    void releaseExclusiveLease(String groupId, String workerId);

    boolean hasExclusiveLease(String workerId);

    List<String> exclusiveLeaseWorkerIds();

    boolean disableDispatch(String groupId, String workerId, DispatchAvailabilitySource source);

    boolean clearDispatchDisable(String groupId, String workerId, DispatchAvailabilitySource source);

    Set<String> activeWorkerIdsByTask(String taskId);

    int activeWorkerCountForTask(String taskId);

    int activeLeaseCountByTaskWorker(String taskId, String workerId);

    void markCandidateStale(String groupId, String workerId, String reason);

    CleanupSummary cleanupExpiredHeartbeats(long nowMillis, int limit);

    CleanupSummary cleanupStaleBucketMembers(String groupId, int limit);
}
