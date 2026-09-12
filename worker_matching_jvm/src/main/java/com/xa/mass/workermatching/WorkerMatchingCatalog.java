package com.xa.mass.workermatching;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Persistent Worker facts and allocation-rule owner. */
public interface WorkerMatchingCatalog extends com.xa.mass.kernel.assignment.WorkerCandidateIndex {

    int MAX_BATCH_SIZE = 100;

    /** Rejects unsupported queries or Groups without an enabled index; performs no selection. */
    void validateWorkerSelector(String workerGroupId, TaskItemWorkerSelector selector);

    /** Creates or replaces complete string Properties for 1..100 Workers in one Group. */
    Map<String, MutationResult> upsertWorkerFactsBatch(
            String workerGroupId,
            Map<String, Map<String, String>> propertiesByWorkerId
    );

    MutationResult patchWorkerPlatformProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> properties
    );

    Map<String, @Nullable WorkerFacts> loadWorkerFacts(
            String workerGroupId,
            List<String> workerIds
    );

    /** Establishes an immutable Task binding to a shared, content-addressed Rule. */
    MutationResult bindTaskAllocationRule(
            String taskId,
            String workerGroupId,
            Map<String, Object> allocationRule
    );

    /** Binds a fixed named Handler; rejects unavailable Group indexes. */
    MutationResult bindTaskRule(String taskId, String workerGroupId, String ruleId);

    /** Resolves up to 100 unique Task IDs in one batch; unavailable Rules map to null. */
    Map<String, @Nullable MatchingRule> loadTaskRules(
            List<String> taskIds
    );

    enum MutationStatus {
        APPLIED,
        UNCHANGED,
        NOT_FOUND,
        CONFLICT,
        INVALID
    }

    record MutationResult(MutationStatus status, @Nullable String reason) {
        public MutationResult {
            Objects.requireNonNull(status, "status");
        }

        public MutationResult(MutationStatus status) {
            this(status, null);
        }
    }

    record WorkerFacts(
            String workerId,
            String workerGroupId,
            Map<String, Object> workerProperties,
            Map<String, Object> platformProperties
    ) {
        public WorkerFacts {
            requireNonBlank(workerId, "workerId");
            requireNonBlank(workerGroupId, "workerGroupId");
            workerProperties = immutableMap(workerProperties);
            platformProperties = immutableMap(platformProperties);
        }
    }

    record MatchingRule(
            String ruleId,
            String workerGroupId,
            @Nullable Map<String, Object> allocationRule
    ) {
        public MatchingRule {
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(workerGroupId, "workerGroupId");
            allocationRule = allocationRule == null ? null : immutableMap(allocationRule);
        }
    }

    private static Map<String, Object> immutableMap(
            Map<String, Object> source
    ) {
        Objects.requireNonNull(source, "mapping");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

}
