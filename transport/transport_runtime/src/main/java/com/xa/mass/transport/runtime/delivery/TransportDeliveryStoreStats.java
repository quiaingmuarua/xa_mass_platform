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
    private final long enqueuedItems;
    private final long drainedItems;
    private final long backpressureRejectedItems;
    private final long invalidItems;
    private final long unavailableItems;
    private final long shutdownClearedItems;

    public TransportDeliveryStoreStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems) {
        this(queuedItems, queueCount, waitingPollers, maxQueuedItems, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public TransportDeliveryStoreStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems,
                                       long oldestQueuedAgeMillis) {
        this(queuedItems, queueCount, waitingPollers, maxQueuedItems, oldestQueuedAgeMillis, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public TransportDeliveryStoreStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems,
                                       long oldestQueuedAgeMillis,
                                       long enqueuedItems,
                                       long drainedItems,
                                       long backpressureRejectedItems,
                                       long invalidItems,
                                       long unavailableItems,
                                       long shutdownClearedItems) {
        this.queuedItems = Math.max(0, queuedItems);
        this.queueCount = Math.max(0, queueCount);
        this.waitingPollers = Math.max(0, waitingPollers);
        this.maxQueuedItems = Math.max(0, maxQueuedItems);
        this.oldestQueuedAgeMillis = Math.max(0L, oldestQueuedAgeMillis);
        this.enqueuedItems = Math.max(0L, enqueuedItems);
        this.drainedItems = Math.max(0L, drainedItems);
        this.backpressureRejectedItems = Math.max(0L, backpressureRejectedItems);
        this.invalidItems = Math.max(0L, invalidItems);
        this.unavailableItems = Math.max(0L, unavailableItems);
        this.shutdownClearedItems = Math.max(0L, shutdownClearedItems);
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

    public long getEnqueuedItems() {
        return enqueuedItems;
    }

    public long getDrainedItems() {
        return drainedItems;
    }

    public long getBackpressureRejectedItems() {
        return backpressureRejectedItems;
    }

    public long getInvalidItems() {
        return invalidItems;
    }

    public long getUnavailableItems() {
        return unavailableItems;
    }

    public long getShutdownClearedItems() {
        return shutdownClearedItems;
    }
}
