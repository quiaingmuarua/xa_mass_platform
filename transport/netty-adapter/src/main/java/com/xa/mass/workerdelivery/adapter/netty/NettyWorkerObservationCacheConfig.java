package com.xa.mass.workerdelivery.adapter.netty;

import java.time.Duration;
import java.util.Objects;

/** Finite cache policy for one Adapter's Worker properties projection. */
public record NettyWorkerObservationCacheConfig(
        Duration freshness,
        long maximumEncodedBytes
) {

    public NettyWorkerObservationCacheConfig {
        Objects.requireNonNull(freshness, "freshness");
        if (freshness.isZero()
                || freshness.isNegative()) {
            throw new IllegalArgumentException("freshness must be positive");
        }
        try {
            freshness.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    "freshness is too large",
                    error
            );
        }
        if (maximumEncodedBytes <= 0) {
            throw new IllegalArgumentException(
                    "maximumEncodedBytes must be positive"
            );
        }
    }
}
