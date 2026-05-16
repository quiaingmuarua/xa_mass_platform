package com.xa.mass.runtime.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime claim target.
 *
 * <p>Current scheduling claims should use {@link #workerLevel(String, String, int)}
 * or {@link #workerLevel(String, String, int, Set)}. The nullable
 * {@code workerContextId} field remains only for runtime compatibility with
 * historical context-backed leases.</p>
 */
public record WorkerClaimTarget(String workerId,
                                String workerContextId,
                                String batchId,
                                int capacity,
                                Set<String> supportedEventCodes) {

    public WorkerClaimTarget(String workerId, String workerContextId, String batchId, int capacity) {
        this(workerId, workerContextId, batchId, capacity, Set.of());
    }

    public static WorkerClaimTarget workerLevel(String workerId, String batchId, int capacity) {
        return workerLevel(workerId, batchId, capacity, Set.of());
    }

    public static WorkerClaimTarget workerLevel(String workerId,
                                                String batchId,
                                                int capacity,
                                                Set<String> supportedEventCodes) {
        return new WorkerClaimTarget(workerId, null, batchId, capacity, supportedEventCodes);
    }

    public WorkerClaimTarget {
        capacity = Math.max(0, capacity);
        supportedEventCodes = normalizeSupportedEventCodes(supportedEventCodes);
    }

    public boolean supportsEvent(String eventCode) {
        if (supportedEventCodes.isEmpty()) {
            return true;
        }
        if (eventCode == null || eventCode.isBlank()) {
            return false;
        }
        return supportedEventCodes.contains(eventCode.trim());
    }

    private static Set<String> normalizeSupportedEventCodes(Set<String> supportedEventCodes) {
        if (supportedEventCodes == null || supportedEventCodes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String eventCode : supportedEventCodes) {
            if (eventCode == null) {
                continue;
            }
            String trimmed = eventCode.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }
}

