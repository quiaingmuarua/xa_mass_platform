package com.xa.mass.worker.runtime.selection;

import java.util.Objects;

/**
 * Persisted selected-worker evidence used by release/final paths after the
 * original selected handle is no longer in memory.
 */
public record SelectedWorkerEvidence(
        String workerId,
        String workerGroupId,
        String selectionScopeKey,
        String selectionToken,
        boolean exclusiveWorkerLock
) {

    public SelectedWorkerEvidence {
        workerId = requireText(workerId, "workerId");
        workerGroupId = requireText(workerGroupId, "workerGroupId");
        selectionScopeKey = normalizeNullable(selectionScopeKey);
        selectionToken = normalizeNullable(selectionToken);
    }

    public static SelectedWorkerEvidence of(String workerId,
                                            String workerGroupId,
                                            String selectionScopeKey,
                                            boolean exclusiveWorkerLock) {
        return new SelectedWorkerEvidence(workerId, workerGroupId, selectionScopeKey, null, exclusiveWorkerLock);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
