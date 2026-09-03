package com.xa.mass.workermatching;

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

    MutationResult createCandidateRule(
            String candidateId,
            String workerGroupId,
            Map<String, Object> allocationRule
    );

    Map<String, @Nullable CandidateRule> loadCandidateRules(
            List<String> candidateIds
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

    record CandidateRule(
            String candidateId,
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        public CandidateRule {
            requireNonBlank(candidateId, "candidateId");
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
