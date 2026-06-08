package com.xa.mass.worker.runtime.candidate;

import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;

import java.util.List;
import java.util.Set;

/**
 * Runtime-neutral task selector consumed by worker candidate source APIs.
 */
public record WorkerTaskSelector(String taskId,
                                 List<String> workerGroupIds,
                                 String adapterNodeId,
                                 String targetWorkerId,
                                 Set<String> candidateBucketKeys) {

    public WorkerTaskSelector {
        taskId = normalizeNullable(taskId);
        workerGroupIds = normalizeList(workerGroupIds);
        adapterNodeId = normalizeNullable(adapterNodeId);
        targetWorkerId = normalizeNullable(targetWorkerId);
        candidateBucketKeys = normalizeCandidateBucketKeys(candidateBucketKeys);
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

    private static Set<String> normalizeCandidateBucketKeys(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY);
        }
        Set<String> normalized = values.stream()
                .map(WorkerTaskSelector::normalizeNullable)
                .filter(value -> value != null)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return normalized.isEmpty()
                ? Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY)
                : java.util.Collections.unmodifiableSet(normalized);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
