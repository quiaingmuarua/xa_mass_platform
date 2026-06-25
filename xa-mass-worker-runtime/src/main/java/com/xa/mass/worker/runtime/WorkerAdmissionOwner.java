package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
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

}
