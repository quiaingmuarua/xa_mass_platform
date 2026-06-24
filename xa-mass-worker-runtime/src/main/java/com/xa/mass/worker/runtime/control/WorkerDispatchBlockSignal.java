package com.xa.mass.worker.runtime.control;

/**
 * Negative-only worker dispatch eligibility signal.
 */
public record WorkerDispatchBlockSignal(
        WorkerDispatchBlockSource source,
        String reason,
        long observedAtMillis,
        long suggestedRecheckAfterMillis
) {

    public WorkerDispatchBlockSignal {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        reason = normalizeNullable(reason);
        observedAtMillis = Math.max(0L, observedAtMillis);
        suggestedRecheckAfterMillis = Math.max(0L, suggestedRecheckAfterMillis);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
