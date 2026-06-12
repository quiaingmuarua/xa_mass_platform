package com.xa.mass.runtime.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime claim target.
 *
 * <p>Claims execute against a concrete worker slot. Scheduling hot paths should
 * carry the worker group evidence that identified the slot; lower-level
 * worker-id-only factories remain support surfaces for tests and non-scheduling
 * callers.</p>
 */
public record WorkerClaimTarget(String workerId,
                                String workerGroupId,
                                String batchId,
                                int capacity,
                                Set<String> supportedEventCodes) {

    public WorkerClaimTarget(String workerId, String batchId, int capacity) {
        this(workerId, null, batchId, capacity, Set.of());
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

    public static WorkerClaimTarget groupScoped(String workerGroupId,
                                                String workerId,
                                                String batchId,
                                                int capacity,
                                                Set<String> supportedEventCodes) {
        return new WorkerClaimTarget(workerId, workerGroupId, batchId, capacity, supportedEventCodes);
    }

    public WorkerClaimTarget {
        workerGroupId = normalizeNullable(workerGroupId);
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

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

