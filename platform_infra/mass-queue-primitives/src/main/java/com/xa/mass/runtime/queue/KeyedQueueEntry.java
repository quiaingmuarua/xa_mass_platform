package com.xa.mass.runtime.queue;

import java.util.Objects;

/**
 * Queue-owned wrapper that preserves the queued value together with the
 * creation timestamp used for queue age diagnostics.
 */
public record KeyedQueueEntry<V>(V value, long createdAtEpochMillis) {

    public KeyedQueueEntry {
        Objects.requireNonNull(value, "value");
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }
}
