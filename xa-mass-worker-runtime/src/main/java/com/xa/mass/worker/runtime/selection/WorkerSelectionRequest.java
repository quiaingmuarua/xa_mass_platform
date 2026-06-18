package com.xa.mass.worker.runtime.selection;

/**
 * Engine-facing request for selected worker handles.
 */
public record WorkerSelectionRequest(
        String selectionScopeKey,
        WorkerSelectionIntent intent,
        int requestedWorkerCount,
        boolean exclusiveWorkerLock
) {

    public WorkerSelectionRequest {
        selectionScopeKey = normalizeNullable(selectionScopeKey);
        intent = intent == null
                ? new WorkerSelectionIntent(null, null, null, null, null, null, null)
                : intent;
        requestedWorkerCount = Math.max(0, requestedWorkerCount);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
