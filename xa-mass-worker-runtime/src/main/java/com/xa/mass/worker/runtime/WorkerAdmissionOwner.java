package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.ReserveResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionResult;
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
        return workerRegistry.tryAcquireExclusiveLease(workerId);
    }

    public void releaseWorkerExclusiveLease(String workerId) {
        workerRegistry.releaseExclusiveLease(workerId);
    }

    public boolean hasWorkerExclusiveLease(String workerId) {
        return workerRegistry.hasExclusiveLease(workerId);
    }

    public List<String> getExclusiveLeaseWorkerIds() {
        return workerRegistry.exclusiveLeaseWorkerIds();
    }

    public WorkerLoadSnapshot getWorkerLoad(String workerId) {
        return workerRegistry.workerAdmissionSnapshot(workerId)
                .map(snapshot -> new WorkerLoadSnapshot(
                        snapshot.workerId(),
                        snapshot.activeLeaseCount(),
                        snapshot.reservedCount(),
                        snapshot.declaredCapacity()
                ))
                .orElseGet(() -> WorkerLoadSnapshot.empty(workerId));
    }

    public int getActiveWorkerCountForTask(String taskId) {
        return workerRegistry.activeWorkerCountForTask(taskId);
    }

    public WorkerAdmissionResult reserveWorkerCapacity(String workerId, String taskId) {
        ReserveResult reserveResult = workerRegistry.tryReserve(workerId, taskId, 1, System.currentTimeMillis());
        return WorkerAdmissionResult.fromReserveResult(reserveResult);
    }

    public boolean confirmWorkerReservation(String workerId, String taskId) {
        return workerRegistry.confirmReservation(workerId, taskId, 1);
    }

    public void releaseWorkerReservation(String workerId, String taskId) {
        workerRegistry.releaseReservation(workerId, taskId, 1);
    }

    public void recordWorkClaimed(String workerId, String taskId) {
        workerRegistry.recordWorkClaimed(workerId, taskId, 1);
    }

    public void recordWorkFinal(String workerId, String taskId) {
        workerRegistry.recordWorkFinal(workerId, taskId, 1);
    }
}
