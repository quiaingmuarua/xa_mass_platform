package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemMatchKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Persistent Worker facts and allocation-rule owner. */
public interface WorkerMatchingCatalog {

    int MAX_BATCH_SIZE = 100;
    MutationResult upsertWorkerFacts(
            String workerId,
            String workerGroupId,
            Map<String, Object> workerProperties
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

    MutationResult createTaskRule(
            String taskId,
            String workerGroupId,
            Map<String, Object> allocationRule
    );

    Map<String, @Nullable TaskRule> loadTaskRules(List<String> taskIds);

    Map<ItemMatchKey, MutationResult> createItemRules(
            List<ItemRule> rules
    );

    Map<ItemMatchKey, @Nullable ItemRule> loadItemRules(
            List<ItemMatchKey> keys
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

    record TaskRule(
            String taskId,
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        public TaskRule {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(workerGroupId, "workerGroupId");
            allocationRule = immutableMap(allocationRule);
        }
    }

    record ItemRule(
            ItemMatchKey key,
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        public ItemRule {
            Objects.requireNonNull(key, "key");
            requireNonBlank(workerGroupId, "workerGroupId");
            allocationRule = immutableMap(allocationRule);
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
