package com.xa.mass.engine.worker;

import com.xa.mass.engine.load.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.WorkerRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Package-local owner for worker runtime admission, lease, and occupancy state.
 */
final class WorkerAdmissionOwner {

    private final WorkerRegistry workerRegistry;

    WorkerAdmissionOwner(WorkerRegistry workerRegistry) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    }

    boolean tryAcquireWorkerExclusiveLease(String workerId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.tryAcquireExclusiveLease(slot.groupId(), slot.workerId()))
                .orElse(false);
    }

    void releaseWorkerExclusiveLease(String workerId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.releaseExclusiveLease(slot.groupId(), slot.workerId()));
    }

    boolean hasWorkerExclusiveLease(String workerId) {
        return workerRegistry.hasExclusiveLease(workerId);
    }

    List<String> getExclusiveLeaseWorkerIds() {
        return workerRegistry.exclusiveLeaseWorkerIds();
    }

    WorkerLoadSnapshot getWorkerLoad(String workerId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> new WorkerLoadSnapshot(
                        slot.workerId(),
                        slot.activeLeaseCount(),
                        slot.reservedCount(),
                        slot.declaredCapacity()
                ))
                .orElseGet(() -> WorkerLoadSnapshot.empty(workerId));
    }

    int getActiveWorkerCountForTask(String taskId) {
        return workerRegistry.activeWorkerCountForTask(taskId);
    }

    boolean tryReserveWorkerCapacity(String workerId, String taskId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.tryReserve(
                        slot.groupId(),
                        slot.workerId(),
                        taskId,
                        1,
                        System.currentTimeMillis()
                ).accepted())
                .orElse(false);
    }

    boolean confirmWorkerReservation(String workerId, String taskId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.confirmReservation(slot.groupId(), slot.workerId(), taskId, 1))
                .orElse(false);
    }

    void releaseWorkerReservation(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.releaseReservation(slot.groupId(), slot.workerId(), taskId, 1));
    }

    void recordWorkClaimed(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.recordWorkClaimed(slot.groupId(), slot.workerId(), taskId, 1));
    }

    void recordWorkFinal(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.recordWorkFinal(slot.groupId(), slot.workerId(), taskId, 1));
    }
}
