package com.xa.mass.runtime.queue;

import java.util.Map;

public record KeyedQueueSnapshot<K>(int queuedItems,
                                    int queueCount,
                                    int waitingPollers,
                                    int maxQueuedItems,
                                    long oldestQueuedAgeMillis,
                                    long enqueuedItems,
                                    long drainedItems,
                                    long backpressureRejectedItems,
                                    long invalidItems,
                                    long unavailableItems,
                                    long shutdownClearedItems,
                                    Map<K, KeyedQueueKeySnapshot> queueByKey) {

    public KeyedQueueSnapshot {
        queuedItems = Math.max(0, queuedItems);
        queueCount = Math.max(0, queueCount);
        waitingPollers = Math.max(0, waitingPollers);
        maxQueuedItems = Math.max(0, maxQueuedItems);
        oldestQueuedAgeMillis = Math.max(0L, oldestQueuedAgeMillis);
        enqueuedItems = Math.max(0L, enqueuedItems);
        drainedItems = Math.max(0L, drainedItems);
        backpressureRejectedItems = Math.max(0L, backpressureRejectedItems);
        invalidItems = Math.max(0L, invalidItems);
        unavailableItems = Math.max(0L, unavailableItems);
        shutdownClearedItems = Math.max(0L, shutdownClearedItems);
        queueByKey = queueByKey == null || queueByKey.isEmpty() ? Map.of() : Map.copyOf(queueByKey);
    }
}
