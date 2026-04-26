package com.xa.mass.transport.runtime.delivery;

/**
 * Runtime direct-send delivery counters for one adapter.
 */
public final class TransportDirectDeliveryStats {

    private final long sentItems;
    private final long offlineItems;
    private final long failedItems;
    private final long invalidItems;
    private final long unavailableItems;

    public TransportDirectDeliveryStats(long sentItems,
                                        long offlineItems,
                                        long failedItems,
                                        long invalidItems,
                                        long unavailableItems) {
        this.sentItems = Math.max(0L, sentItems);
        this.offlineItems = Math.max(0L, offlineItems);
        this.failedItems = Math.max(0L, failedItems);
        this.invalidItems = Math.max(0L, invalidItems);
        this.unavailableItems = Math.max(0L, unavailableItems);
    }

    public long getSentItems() {
        return sentItems;
    }

    public long getOfflineItems() {
        return offlineItems;
    }

    public long getFailedItems() {
        return failedItems;
    }

    public long getInvalidItems() {
        return invalidItems;
    }

    public long getUnavailableItems() {
        return unavailableItems;
    }
}
