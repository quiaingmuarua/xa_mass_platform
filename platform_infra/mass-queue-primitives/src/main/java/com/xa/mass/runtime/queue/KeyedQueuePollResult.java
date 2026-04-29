package com.xa.mass.runtime.queue;

import java.util.List;
import java.util.Objects;

public final class KeyedQueuePollResult<V> {

    private final KeyedQueuePollStatus status;
    private final List<KeyedQueueEntry<V>> items;

    public KeyedQueuePollResult(KeyedQueuePollStatus status, List<KeyedQueueEntry<V>> items) {
        this.status = Objects.requireNonNull(status, "status");
        this.items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    public static <V> KeyedQueuePollResult<V> delivered(List<KeyedQueueEntry<V>> items) {
        return new KeyedQueuePollResult<>(KeyedQueuePollStatus.DELIVERED, items);
    }

    public static <V> KeyedQueuePollResult<V> empty() {
        return new KeyedQueuePollResult<>(KeyedQueuePollStatus.EMPTY, List.of());
    }

    public static <V> KeyedQueuePollResult<V> invalid() {
        return new KeyedQueuePollResult<>(KeyedQueuePollStatus.INVALID_REQUEST, List.of());
    }

    public static <V> KeyedQueuePollResult<V> unavailable() {
        return new KeyedQueuePollResult<>(KeyedQueuePollStatus.UNAVAILABLE, List.of());
    }

    public static <V> KeyedQueuePollResult<V> shutdown() {
        return new KeyedQueuePollResult<>(KeyedQueuePollStatus.SHUTDOWN, List.of());
    }

    public KeyedQueuePollStatus status() {
        return status;
    }

    public List<KeyedQueueEntry<V>> items() {
        return items;
    }
}
