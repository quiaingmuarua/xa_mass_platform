package com.xa.mass.engine.worker;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker-level dispatch gate owner.
 *
 * <p>This owner controls only whether a worker may receive new dispatches. It
 * does not own transport reachability, worker model status, load, or bounded
 * diagnostic state projections.</p>
 */
public final class WorkerDispatchAvailabilityOwner {

    public enum DispatchAvailability {
        ENABLED,
        DRAINING_DISABLED
    }

    private final ConcurrentHashMap<String, DispatchAvailability> availabilityByWorkerId =
            new ConcurrentHashMap<>();

    public boolean isDispatchEnabled(String workerId) {
        return availabilityOf(workerId) == DispatchAvailability.ENABLED;
    }

    public DispatchAvailability availabilityOf(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        return availabilityByWorkerId.getOrDefault(normalizedWorkerId, DispatchAvailability.ENABLED);
    }

    public boolean disableForDraining(String workerId, String reason) {
        String normalizedWorkerId = requireWorkerId(workerId);
        return availabilityByWorkerId.put(normalizedWorkerId,
                DispatchAvailability.DRAINING_DISABLED) != DispatchAvailability.DRAINING_DISABLED;
    }

    public boolean enable(String workerId, String reason) {
        String normalizedWorkerId = requireWorkerId(workerId);
        return availabilityByWorkerId.remove(normalizedWorkerId) != null;
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }
}
