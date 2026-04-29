package com.xa.mass.runtime.queue;

public record KeyedQueueKeySnapshot(int queuedItems,
                                    int waitingPollers,
                                    long oldestQueuedAgeMillis,
                                    long backpressureRejectedItems) {

    public KeyedQueueKeySnapshot {
        queuedItems = Math.max(0, queuedItems);
        waitingPollers = Math.max(0, waitingPollers);
        oldestQueuedAgeMillis = Math.max(0L, oldestQueuedAgeMillis);
        backpressureRejectedItems = Math.max(0L, backpressureRejectedItems);
    }
}
