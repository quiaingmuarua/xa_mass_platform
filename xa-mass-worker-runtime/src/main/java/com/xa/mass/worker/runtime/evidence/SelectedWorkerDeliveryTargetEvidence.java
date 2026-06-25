package com.xa.mass.worker.runtime.evidence;

/**
 * Worker-runtime projection from an already selected worker to its current
 * opaque adapter mailbox delivery target.
 *
 * <p>This is an assigned-delivery target fact, not worker reachability or
 * scheduling eligibility truth.</p>
 */
public record SelectedWorkerDeliveryTargetEvidence(String workerId,
                                                   String adapterMailboxKey,
                                                   long generation,
                                                   long observedAtEpochMillis,
                                                   long expiresAtEpochMillis) {

    public SelectedWorkerDeliveryTargetEvidence {
        workerId = requireText(workerId, "workerId");
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        generation = Math.max(0L, generation);
        observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
        expiresAtEpochMillis = Math.max(0L, expiresAtEpochMillis);
    }

    public boolean isDeliverable(long nowEpochMillis) {
        return expiresAtEpochMillis == Long.MAX_VALUE || expiresAtEpochMillis > Math.max(0L, nowEpochMillis);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
