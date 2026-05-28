package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.worker.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.ReserveResult;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerAdmissionResult;
import com.xa.mass.runtime.worker.WorkerRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Worker runtime owner for worker runtime admission, lease, and occupancy state.
 */
public final class WorkerAdmissionOwner {

    private final WorkerRegistry workerRegistry;

    public WorkerAdmissionOwner(WorkerRegistry workerRegistry) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    }

    public boolean tryAcquireWorkerExclusiveLease(String workerId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.tryAcquireExclusiveLease(slot.groupId(), slot.workerId()))
                .orElse(false);
    }

    public void releaseWorkerExclusiveLease(String workerId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.releaseExclusiveLease(slot.groupId(), slot.workerId()));
    }

    public boolean hasWorkerExclusiveLease(String workerId) {
        return workerRegistry.hasExclusiveLease(workerId);
    }

    public List<String> getExclusiveLeaseWorkerIds() {
        return workerRegistry.exclusiveLeaseWorkerIds();
    }

    public WorkerLoadSnapshot getWorkerLoad(String workerId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> new WorkerLoadSnapshot(
                        slot.workerId(),
                        slot.activeLeaseCount(),
                        slot.reservedCount(),
                        slot.declaredCapacity()
                ))
                .orElseGet(() -> WorkerLoadSnapshot.empty(workerId));
    }

    public int getActiveWorkerCountForTask(String taskId) {
        return workerRegistry.activeWorkerCountForTask(taskId);
    }

    public WorkerAdmissionResult reserveWorkerCapacity(String workerId, String taskId) {
        ReserveResult reserveResult = workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.tryReserve(
                        slot.groupId(),
                        slot.workerId(),
                        taskId,
                        1,
                        System.currentTimeMillis()
                ))
                .orElseGet(() -> ReserveResult.rejected(ReserveStatus.MISSING_SLOT, "worker slot missing"));
        return WorkerAdmissionResult.fromReserveResult(reserveResult);
    }

    public boolean confirmWorkerReservation(String workerId, String taskId) {
        return workerRegistry.slotByWorkerId(workerId)
                .map(slot -> workerRegistry.confirmReservation(slot.groupId(), slot.workerId(), taskId, 1))
                .orElse(false);
    }

    public void releaseWorkerReservation(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.releaseReservation(slot.groupId(), slot.workerId(), taskId, 1));
    }

    public void recordWorkClaimed(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.recordWorkClaimed(slot.groupId(), slot.workerId(), taskId, 1));
    }

    public void recordWorkFinal(String workerId, String taskId) {
        workerRegistry.slotByWorkerId(workerId)
                .ifPresent(slot -> workerRegistry.recordWorkFinal(slot.groupId(), slot.workerId(), taskId, 1));
    }
}
