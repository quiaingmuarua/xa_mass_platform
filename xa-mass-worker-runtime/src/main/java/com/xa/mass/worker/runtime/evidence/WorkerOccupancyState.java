package com.xa.mass.worker.runtime.evidence;

/**
 * Diagnostic worker occupancy dimension derived from worker runtime counters.
 *
 * <p>The canonical truth remains capacity, reservation, active-lease, and
 * exclusive-lock facts owned by the worker registry. This value is only a
 * compact observation for diagnostics and read models.</p>
 */
public enum WorkerOccupancyState {
    FREE,
    RESERVED,
    OCCUPIED,
    CAPACITY_FULL;

    public boolean available() {
        return this == FREE;
    }

    public static WorkerOccupancyState fromLoad(WorkerLoadSnapshot load, boolean exclusiveLeaseHeld) {
        WorkerLoadSnapshot snapshot = load == null ? WorkerLoadSnapshot.empty(null) : load;
        return fromCounters(snapshot.activeLeaseCount(), snapshot.reservedCount(),
                snapshot.declaredCapacity(), exclusiveLeaseHeld);
    }

    public static WorkerOccupancyState fromCounters(int activeLeaseCount,
                                                    int reservedCount,
                                                    int declaredCapacity,
                                                    boolean exclusiveLeaseHeld) {
        int active = Math.max(0, activeLeaseCount);
        int reserved = Math.max(0, reservedCount);
        int capacity = Math.max(1, declaredCapacity);
        if (exclusiveLeaseHeld || active + reserved >= capacity) {
            return CAPACITY_FULL;
        }
        if (reserved > 0) {
            return RESERVED;
        }
        if (active > 0) {
            return OCCUPIED;
        }
        return FREE;
    }
}
