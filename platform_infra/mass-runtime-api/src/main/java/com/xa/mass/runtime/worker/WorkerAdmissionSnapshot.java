package com.xa.mass.runtime.worker;

/**
 * Semantic worker admission/occupancy read model exposed by WorkerRegistry.
 *
 * <p>This is intentionally narrower than {@link WorkerSlot}: callers should
 * not need the registry's physical slot aggregate to read load evidence.</p>
 */
public record WorkerAdmissionSnapshot(
        String workerId,
        int activeLeaseCount,
        int reservedCount,
        int declaredCapacity
) {

    public WorkerAdmissionSnapshot {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        workerId = workerId.trim();
        activeLeaseCount = Math.max(0, activeLeaseCount);
        reservedCount = Math.max(0, reservedCount);
        declaredCapacity = Math.max(1, declaredCapacity);
    }
}
