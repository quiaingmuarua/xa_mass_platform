package com.xa.mass.worker.runtime.evidence;

/**
 * Immutable observational load view for one worker.
 */
public record WorkerLoadSnapshot(
        String workerId,
        int activeLeaseCount,
        int reservedCount,
        int declaredCapacity
) {

    public WorkerLoadSnapshot {
        activeLeaseCount = Math.max(0, activeLeaseCount);
        reservedCount = Math.max(0, reservedCount);
        declaredCapacity = Math.max(1, declaredCapacity);
    }

    public static WorkerLoadSnapshot empty(String workerId) {
        return new WorkerLoadSnapshot(workerId, 0, 0, 1);
    }

    public int observedLoadCount() {
        return activeLeaseCount + reservedCount;
    }

    public double estimatedLoadRatio() {
        return observedLoadCount() / (double) declaredCapacity;
    }
}
