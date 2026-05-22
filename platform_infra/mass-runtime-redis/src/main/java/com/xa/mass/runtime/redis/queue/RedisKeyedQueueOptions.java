package com.xa.mass.runtime.redis.queue;

import java.time.Duration;
import java.util.Objects;

/**
 * Narrow runtime options for Redis-backed keyed queues.
 */
public record RedisKeyedQueueOptions(int maxQueuedItems,
                                     Duration pollSleepInterval,
                                     Duration snapshotCacheWindow) {

    private static final Duration DEFAULT_POLL_SLEEP_INTERVAL = Duration.ofMillis(100);
    private static final Duration DEFAULT_SNAPSHOT_CACHE_WINDOW = Duration.ofMillis(250);

    public RedisKeyedQueueOptions {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be positive");
        }
        pollSleepInterval = requirePositiveDuration(pollSleepInterval, "pollSleepInterval");
        snapshotCacheWindow = requirePositiveDuration(snapshotCacheWindow, "snapshotCacheWindow");
    }

    public static RedisKeyedQueueOptions defaults(int maxQueuedItems) {
        return new RedisKeyedQueueOptions(
                maxQueuedItems,
                DEFAULT_POLL_SLEEP_INTERVAL,
                DEFAULT_SNAPSHOT_CACHE_WINDOW
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
