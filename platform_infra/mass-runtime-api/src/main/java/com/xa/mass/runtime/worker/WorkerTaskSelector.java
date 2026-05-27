package com.xa.mass.runtime.worker;

import java.util.List;
import java.util.Set;

/**
 * Runtime-neutral task selector consumed by worker candidate source APIs.
 */
public record WorkerTaskSelector(String taskId,
                                 List<String> workerGroupIds,
                                 String adapterNodeId,
                                 String targetWorkerId,
                                 Set<String> routeBucketKeys) {

    public WorkerTaskSelector {
        taskId = normalizeNullable(taskId);
        workerGroupIds = normalizeList(workerGroupIds);
        adapterNodeId = normalizeNullable(adapterNodeId);
        targetWorkerId = normalizeNullable(targetWorkerId);
        routeBucketKeys = normalizeRouteBucketKeys(routeBucketKeys);
    }

    public boolean targetsWorker() {
        return targetWorkerId != null;
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(WorkerTaskSelector::normalizeNullable)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private static Set<String> normalizeRouteBucketKeys(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of(WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY);
        }
        Set<String> normalized = values.stream()
                .map(WorkerTaskSelector::normalizeNullable)
                .filter(value -> value != null)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return normalized.isEmpty()
                ? Set.of(WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY)
                : java.util.Collections.unmodifiableSet(normalized);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
