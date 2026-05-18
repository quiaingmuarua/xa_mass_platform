package com.xa.mass.runtime.api;

import java.time.Instant;

/**
 * Combined result of atomically reading the active lease / work snapshot and
 * applying a {@link TaskWorkResult} through {@link TaskWorkRuntime}.
 *
 * <p>Carrying the pre-apply lease and work fields in one call eliminates the
 * three separate round-trips ({@code getActiveLease}, {@code getWork},
 * {@code applyResult}) that the engine previously required per callback. All
 * three are now a single atomic operation in both the in-memory and Redis
 * implementations.</p>
 *
 * <p>The lease and work fields are {@code null} / {@code 0} when no active
 * lease existed at apply time (outcome status is
 * {@link ResultApplyStatus#NO_ACTIVE_LEASE} or
 * {@link ResultApplyStatus#STALE_LEASE} without a captured snapshot).</p>
 */
public record RuntimeResultApplyContext(
        ResultApplyOutcome outcome,
        /** Worker id from the active lease snapshot captured before apply. */
        String workerId,
        String batchId,
        /** The active lease token captured before apply. */
        String activeLeaseToken,
        /** Payload reference from the lease or work envelope. */
        String payloadRef,
        /** Retry count on the lease (= attempt number - 1). */
        int retryCount,
        /** Max retry count from the work envelope. */
        int maxRetryCount,
        /** Wall-clock instant when the lease was originally granted. */
        Instant leasedAt
) {

    /** {@code true} when an active lease snapshot was captured. */
    public boolean hasLeaseSnapshot() {
        return activeLeaseToken != null || workerId != null;
    }

    /** Factory for the no-active-lease outcome (duplicate / late callback). */
    public static RuntimeResultApplyContext noLease(ResultApplyOutcome outcome) {
        return new RuntimeResultApplyContext(
                outcome, null, null, null, null, 0, 0, null);
    }

    /**
     * Factory for outcomes where a lease snapshot was available (success,
     * retry, finalized failure, or stale-token rejection).
     */
    public static RuntimeResultApplyContext withSnapshot(ResultApplyOutcome outcome,
                                                         String workerId,
                                                         String batchId,
                                                         String activeLeaseToken,
                                                         String payloadRef,
                                                         int retryCount,
                                                         int maxRetryCount,
                                                         Instant leasedAt) {
        return new RuntimeResultApplyContext(
                outcome, workerId, batchId,
                activeLeaseToken, payloadRef, retryCount, maxRetryCount, leasedAt);
    }
}
