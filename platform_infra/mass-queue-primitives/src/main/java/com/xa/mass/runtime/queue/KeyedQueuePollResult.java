package com.xa.mass.runtime.queue;

import java.util.List;
import java.util.Objects;

public final class KeyedQueuePollResult {

    private final KeyedQueuePollStatus status;
    private final List<KeyedQueueEntry> items;

    public KeyedQueuePollResult(KeyedQueuePollStatus status, List<KeyedQueueEntry> items) {
        this.status = Objects.requireNonNull(status, "status");
        this.items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    public static KeyedQueuePollResult delivered(List<KeyedQueueEntry> items) {
        return new KeyedQueuePollResult(KeyedQueuePollStatus.DELIVERED, items);
    }

    public static KeyedQueuePollResult empty() {
        return new KeyedQueuePollResult(KeyedQueuePollStatus.EMPTY, List.of());
    }

    public static KeyedQueuePollResult invalid() {
        return new KeyedQueuePollResult(KeyedQueuePollStatus.INVALID_REQUEST, List.of());
    }

    public static KeyedQueuePollResult unavailable() {
        return new KeyedQueuePollResult(KeyedQueuePollStatus.UNAVAILABLE, List.of());
    }

    public static KeyedQueuePollResult shutdown() {
        return new KeyedQueuePollResult(KeyedQueuePollStatus.SHUTDOWN, List.of());
    }

    public KeyedQueuePollStatus status() {
        return status;
    }

    public List<KeyedQueueEntry> items() {
        return items;
    }
}
