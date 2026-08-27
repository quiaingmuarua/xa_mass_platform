package com.xa.mass.kernel.pacer;

import java.time.Duration;
import java.util.Objects;

record ResultLane(
        ResultLaneId id,
        int batchLimit,
        long idlePollIntervalMillis,
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
        if (idlePollIntervalMillis < 1) {
            throw new IllegalArgumentException(
                    "idlePollIntervalMillis must be positive"
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

    long idlePollIntervalNanos() {
        return Duration.ofMillis(idlePollIntervalMillis).toNanos();
    }
}
