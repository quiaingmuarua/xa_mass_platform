package com.xa.mass.engine.worker;

import java.util.EnumSet;
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

    public enum DispatchAvailabilitySource {
        WORKER_STATE,
        WORKER_COMMAND,
        NODE_GROUP_BINDING
    }

    private final ConcurrentHashMap<String, EnumSet<DispatchAvailabilitySource>> disabledSourcesByWorkerId =
            new ConcurrentHashMap<>();

    public synchronized boolean isDispatchEnabled(String workerId) {
        return availabilityOf(workerId) == DispatchAvailability.ENABLED;
    }

    public synchronized DispatchAvailability availabilityOf(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        EnumSet<DispatchAvailabilitySource> disabledSources = disabledSourcesByWorkerId.get(normalizedWorkerId);
        return disabledSources == null || disabledSources.isEmpty()
                ? DispatchAvailability.ENABLED
                : DispatchAvailability.DRAINING_DISABLED;
    }

    public synchronized boolean disableForDraining(String workerId, String reason) {
        return disableForDraining(workerId, DispatchAvailabilitySource.WORKER_STATE, reason);
    }

    public synchronized boolean disableForDraining(String workerId,
                                                   DispatchAvailabilitySource source,
                                                   String reason) {
        String normalizedWorkerId = requireWorkerId(workerId);
        DispatchAvailabilitySource normalizedSource = requireSource(source);
        EnumSet<DispatchAvailabilitySource> disabledSources = disabledSourcesByWorkerId.computeIfAbsent(
                normalizedWorkerId,
                ignored -> EnumSet.noneOf(DispatchAvailabilitySource.class)
        );
        return disabledSources.add(normalizedSource);
    }

    public synchronized boolean enable(String workerId, String reason) {
        String normalizedWorkerId = requireWorkerId(workerId);
        return disabledSourcesByWorkerId.remove(normalizedWorkerId) != null;
    }

    public synchronized boolean clearSource(String workerId,
                                            DispatchAvailabilitySource source,
                                            String reason) {
        String normalizedWorkerId = requireWorkerId(workerId);
        DispatchAvailabilitySource normalizedSource = requireSource(source);
        EnumSet<DispatchAvailabilitySource> disabledSources = disabledSourcesByWorkerId.get(normalizedWorkerId);
        if (disabledSources == null || !disabledSources.remove(normalizedSource)) {
            return false;
        }
        if (disabledSources.isEmpty()) {
            disabledSourcesByWorkerId.remove(normalizedWorkerId);
        }
        return true;
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }

    private DispatchAvailabilitySource requireSource(DispatchAvailabilitySource source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        return source;
    }
}
