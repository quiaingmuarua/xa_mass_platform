package com.xa.mass.runtime.worker.slot;

import java.util.Map;
import java.util.Objects;

/**
 * Stable worker scheduling metadata stored beside the score index.
 */
public record WorkerScoreBandSlotMetadata(
        String homeBucketId,
        String workerGroupId,
        String workerId,
        String transportHint,
        String dispatchRecoveryMode,
        Map<String, String> attributes,
        int declaredCapacity
) {

    public static final String RECOVERY_EXPLICIT_ONLY = "EXPLICIT_ONLY";
    public static final String RECOVERY_FRESHNESS_EVIDENCE = "FRESHNESS_EVIDENCE";

    public WorkerScoreBandSlotMetadata {
        homeBucketId = requireNonBlank(homeBucketId, "homeBucketId");
        workerGroupId = requireNonBlank(workerGroupId, "workerGroupId");
        workerId = requireNonBlank(workerId, "workerId");
        transportHint = normalizeNullable(transportHint);
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        dispatchRecoveryMode = normalizeRecoveryMode(dispatchRecoveryMode, attributes);
        declaredCapacity = Math.max(1, declaredCapacity);
    }

    public static WorkerScoreBandSlotMetadata worker(String workerGroupId,
                                                     String workerId,
                                                     String transportHint,
                                                     Map<String, String> attributes,
                                                     int declaredCapacity) {
        return new WorkerScoreBandSlotMetadata(
                workerGroupId,
                workerGroupId,
                workerId,
                transportHint,
                null,
                attributes,
                declaredCapacity
        );
    }

    public boolean freshnessEvidenceRecoveryAllowed() {
        return RECOVERY_FRESHNESS_EVIDENCE.equals(dispatchRecoveryMode);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeRecoveryMode(String value, Map<String, String> attributes) {
        String raw = normalizeNullable(value);
        if (raw == null && attributes != null) {
            raw = normalizeNullable(attributes.get("dispatchRecoveryMode"));
        }
        if (raw == null) {
            return RECOVERY_EXPLICIT_ONLY;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return RECOVERY_FRESHNESS_EVIDENCE.equals(normalized) ? normalized : RECOVERY_EXPLICIT_ONLY;
    }
}
