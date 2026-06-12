package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.runtime.worker.ReserveResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionStatus;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
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

    public WorkerAdmissionResult reserveWorkerCapacity(WorkerAdmissionTarget target) {
        if (target == null) {
            return WorkerAdmissionResult.rejected(WorkerAdmissionStatus.MISSING_SLOT, "worker admission target missing");
        }
        ReserveResult reserveResult = workerRegistry.tryReserve(
                target.workerGroupId(),
                target.workerId(),
                target.taskId(),
                target.permits(),
                System.currentTimeMillis());
        return WorkerAdmissionResult.fromReserveResult(reserveResult);
    }

    public boolean confirmWorkerReservation(WorkerAdmissionTarget target) {
        if (target == null) {
            return false;
        }
        return workerRegistry.confirmReservation(target.workerGroupId(), target.workerId(), target.taskId(), target.permits());
    }

    public void releaseWorkerReservation(WorkerAdmissionTarget target) {
        if (target == null) {
            return;
        }
        workerRegistry.releaseReservation(target.workerGroupId(), target.workerId(), target.taskId(), target.permits());
    }

    public void recordWorkClaimed(WorkerAdmissionTarget target) {
        if (target == null) {
            return;
        }
        workerRegistry.recordWorkClaimed(target.workerGroupId(), target.workerId(), target.taskId(), target.permits());
    }

    public void recordWorkFinal(WorkerAdmissionTarget target) {
        if (target == null) {
            return;
        }
        workerRegistry.recordWorkFinal(target.workerGroupId(), target.workerId(), target.taskId(), target.permits());
    }
}
