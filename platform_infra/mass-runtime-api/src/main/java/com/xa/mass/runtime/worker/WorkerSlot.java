package com.xa.mass.runtime.worker;


import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable current worker slot view used by WorkerRegistry admission.
 */
public record WorkerSlot(
        WorkerMeta meta,
        int declaredCapacity,
        Set<EventKey> eventBindingCeiling,
        int activeLeaseCount,
        int reservedCount,
        Map<String, Integer> activeLeaseCountByTask,
        Set<DispatchAvailabilitySource> disabledSources,
        boolean exclusiveLeaseHeld,
        boolean removing,
        String removingReason
) {

    public WorkerSlot {
        if (meta == null) {
            throw new IllegalArgumentException("meta must not be null");
        }
        declaredCapacity = Math.max(1, declaredCapacity);
        eventBindingCeiling = eventBindingCeiling == null ? Set.of() : Set.copyOf(eventBindingCeiling);
        activeLeaseCount = Math.max(0, activeLeaseCount);
        reservedCount = Math.max(0, reservedCount);
        activeLeaseCountByTask = activeLeaseCountByTask == null
                ? Map.of()
                : Map.copyOf(activeLeaseCountByTask);
        disabledSources = disabledSources == null || disabledSources.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(disabledSources));
        removingReason = normalizeNullable(removingReason);
    }

    public String workerId() {
        return meta.workerId();
    }

    public String groupId() {
        return meta.groupId();
    }

    public boolean dispatchEnabled() {
        return disabledSources.isEmpty();
    }

    public int occupiedPermits() {
        return activeLeaseCount + reservedCount;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
