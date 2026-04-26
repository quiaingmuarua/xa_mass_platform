package com.xa.mass.transport.runtime.delivery;

/**
 * Runtime delivery store snapshot for admission-control and HA diagnostics.
 */
public final class TransportDeliveryStoreStats {

    private final int queuedItems;
    private final int queueCount;
    private final int waitingPollers;
    private final int maxQueuedItems;
    private final long oldestQueuedAgeMillis;

    public TransportDeliveryStoreStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems) {
        this(queuedItems, queueCount, waitingPollers, maxQueuedItems, 0L);
    }

    public TransportDeliveryStoreStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems,
                                       long oldestQueuedAgeMillis) {
        this.queuedItems = Math.max(0, queuedItems);
        this.queueCount = Math.max(0, queueCount);
        this.waitingPollers = Math.max(0, waitingPollers);
        this.maxQueuedItems = Math.max(0, maxQueuedItems);
        this.oldestQueuedAgeMillis = Math.max(0L, oldestQueuedAgeMillis);
    }

    public int getQueuedItems() {
        return queuedItems;
    }

    public int getQueueCount() {
        return queueCount;
    }

    public int getWaitingPollers() {
        return waitingPollers;
    }

    public int getMaxQueuedItems() {
        return maxQueuedItems;
    }

    public long getOldestQueuedAgeMillis() {
        return oldestQueuedAgeMillis;
    }
}
