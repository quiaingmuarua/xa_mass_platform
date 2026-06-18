package com.xa.mass.transport.runtime.delivery;

import java.util.Map;

/**
 * Runtime transport delivery diagnostics snapshot assembled at service level.
 * Queue/store metrics come from {@link TransportDeliveryStoreStats}.
 */
public final class TransportDeliveryServiceStats {

    private final TransportDeliveryStoreStats storeStats;

    public TransportDeliveryServiceStats(TransportDeliveryStoreStats storeStats) {
        this.storeStats = storeStats == null
                ? new TransportDeliveryStoreStats(0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of())
                : storeStats;
    }

    public int getQueuedItems() { return storeStats.getQueuedItems(); }
    public int getQueueCount() { return storeStats.getQueueCount(); }
    public int getWaitingPollers() { return storeStats.getWaitingPollers(); }
    public int getMaxQueuedItems() { return storeStats.getMaxQueuedItems(); }
    public long getOldestQueuedAgeMillis() { return storeStats.getOldestQueuedAgeMillis(); }
    public long getEnqueuedItems() { return storeStats.getEnqueuedItems(); }
    public long getDrainedItems() { return storeStats.getDrainedItems(); }
    public long getBackpressureRejectedItems() { return storeStats.getBackpressureRejectedItems(); }
    public long getInvalidItems() { return storeStats.getInvalidItems(); }
    public long getUnavailableItems() { return storeStats.getUnavailableItems(); }
    public long getShutdownClearedItems() { return storeStats.getShutdownClearedItems(); }
    public Map<String, TransportDeliveryQueueStats> getQueueByAdapter() { return storeStats.getQueueByAdapter(); }
    public TransportDeliveryStoreStats getStoreStats() { return storeStats; }
}
