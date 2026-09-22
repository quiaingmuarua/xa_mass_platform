package com.xa.mass.workermatching;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Accepted Worker Properties, independent Platform patches and bounded Facts observation. */
public interface WorkerProperties {

    int MAX_BATCH_SIZE = 100;

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
