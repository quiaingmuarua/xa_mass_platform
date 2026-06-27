package com.xa.mass.runtime.redis.queue;

import java.time.Duration;
import java.util.Objects;

/**
 * Narrow runtime options for Redis-backed keyed queues.
 */
public record RedisKeyedQueueOptions(int maxQueuedItems,
                                     Duration pollSleepInterval) {

    private static final Duration DEFAULT_POLL_SLEEP_INTERVAL = Duration.ofMillis(100);

    public RedisKeyedQueueOptions {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be positive");
        }
        pollSleepInterval = requirePositiveDuration(pollSleepInterval, "pollSleepInterval");
    }

    public static RedisKeyedQueueOptions defaults(int maxQueuedItems) {
        return new RedisKeyedQueueOptions(
                maxQueuedItems,
                DEFAULT_POLL_SLEEP_INTERVAL
        );
    }

    private static Duration requirePositiveDuration(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }
}
