package com.xa.mass.runtime.worker;

/**
 * Registry-owned metadata for a source-scoped dispatch block.
 *
 * <p>This is not a public transport signal. Worker-runtime maps external
 * negative evidence into this internal registry shape before mutating
 * dispatch eligibility.</p>
 */
public record WorkerDispatchBlockRecord(
        DispatchAvailabilitySource source,
        String reason,
        long observedAtMillis,
        long suggestedRecheckAfterMillis
) {

    public WorkerDispatchBlockRecord {
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
