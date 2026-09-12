package com.xa.mass.workermatching;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Worker facts, fixed Rule bindings and bounded eligibility index queries. */
public interface WorkerMatchingCatalog extends com.xa.mass.kernel.assignment.WorkerCandidateIndex {

    int MAX_BATCH_SIZE = 100;
    String DEFAULT_RULE_ID = "worker.default";

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

    /** Binds a fixed named Handler; rejects unavailable Group indexes. */
    MutationResult bindTaskRule(String taskId, String workerGroupId, String ruleId);

    /** Resolves up to 100 unique Task IDs in one batch; unavailable Rules map to null. */
    Map<String, @Nullable TaskRuleBinding> loadTaskBindings(
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

    /** A Task binding snapshot, not a persisted Rule definition or lifecycle. */
    record TaskRuleBinding(
            String ruleId,
            String workerGroupId
    ) {
        public TaskRuleBinding {
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(workerGroupId, "workerGroupId");
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
