package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Worker facts, Pool supply admission and bounded eligibility index queries. */
public interface WorkerMatchingCatalog extends com.xa.mass.kernel.assignment.WorkerMatching {

    int MAX_BATCH_SIZE = 100;

    /** Idempotent Server admission, without Redis reads or stock changes. */
    WorkerQuery normalizeQuery(String workerGroupId, WorkerQuery query);

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

    /** Normalizes explicit immutable supply, including an empty list, without defaults, Redis or stock access. */
    List<RefillTarget> normalizeRefill(String workerGroupId, List<RefillTarget> declarations);

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
