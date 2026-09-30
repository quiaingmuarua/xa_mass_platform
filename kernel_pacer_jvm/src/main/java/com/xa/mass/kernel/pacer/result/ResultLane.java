package com.xa.mass.kernel.pacer.result;

import java.time.Duration;
import java.util.Objects;

record ResultLane(
        ResultLaneId id,
        int batchLimit,
        long idleBackoffMinMillis,
        long idleBackoffStepMillis,
        long idleBackoffMaxMillis,
        int targetConcurrency,
        int maxConcurrency,
        ResultBatchConsumer consumer,
        ResultBatchPolicy policy
) {

    ResultLane {
        Objects.requireNonNull(id, "id");
        if (batchLimit < 1 || batchLimit > 100) {
            throw new IllegalArgumentException(
                    "batchLimit must be between 1 and 100"
            );
        }
        if (idleBackoffMinMillis < 1) {
            throw new IllegalArgumentException(
                    "idleBackoffMinMillis must be positive"
            );
        }
        if (idleBackoffStepMillis < 0) {
            throw new IllegalArgumentException(
                    "idleBackoffStepMillis must not be negative"
            );
        }
        if (idleBackoffMaxMillis < idleBackoffMinMillis) {
            throw new IllegalArgumentException(
                    "idleBackoffMaxMillis must not be below idleBackoffMinMillis"
            );
        }
        if (targetConcurrency < 1) {
            throw new IllegalArgumentException(
                    "targetConcurrency must be positive"
            );
        }
        if (maxConcurrency < targetConcurrency) {
            throw new IllegalArgumentException(
                    "maxConcurrency must be at least targetConcurrency"
            );
        }
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(policy, "policy");
    }

    /** A fixed idle interval: minimum and maximum are equal and the ramp has no step. */
    ResultLane(
            ResultLaneId id,
            int batchLimit,
            long idleIntervalMillis,
            int targetConcurrency,
            int maxConcurrency,
            ResultBatchConsumer consumer,
            ResultBatchPolicy policy
    ) {
        this(id, batchLimit, idleIntervalMillis, 0, idleIntervalMillis,
                targetConcurrency, maxConcurrency, consumer, policy);
    }

    /** The idle wait after the given one: one step longer, capped at the maximum. */
    long nextIdleBackoffMillis(long currentMillis) {
        return Math.min(currentMillis + idleBackoffStepMillis, idleBackoffMaxMillis);
    }

    static long nanos(long millis) {
        return Duration.ofMillis(millis).toNanos();
    }
}
