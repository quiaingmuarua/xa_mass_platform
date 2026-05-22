package com.xa.mass.transport.runtime.delivery;

import java.util.Map;

/**
 * Runtime transport delivery diagnostics snapshot assembled at service level.
 * Queue/store metrics come from {@link TransportDeliveryStoreStats}; direct-send
 * counters are owned by {@link TransportDeliveryService}.
 *
 * <p>This is a convenience flattening of queue-path store diagnostics plus
 * direct-send counters. For queue semantics, prefer reading
 * {@link #getStoreStats()} as the source of truth and treat the flattened
 * best-effort fields accordingly.
 */
public final class TransportDeliveryServiceStats {

    private final TransportDeliveryStoreStats storeStats;
    private final long directSentItems;
    private final long directOfflineItems;
    private final long directFailedItems;
    private final long directInvalidItems;
    private final long directUnavailableItems;

    public TransportDeliveryServiceStats(TransportDeliveryStoreStats storeStats,
                                         long directSentItems,
                                         long directOfflineItems,
                                         long directFailedItems,
                                         long directInvalidItems,
                                         long directUnavailableItems) {
        this.storeStats = storeStats == null
                ? new TransportDeliveryStoreStats(0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of())
                : storeStats;
        this.directSentItems = Math.max(0L, directSentItems);
        this.directOfflineItems = Math.max(0L, directOfflineItems);
        this.directFailedItems = Math.max(0L, directFailedItems);
        this.directInvalidItems = Math.max(0L, directInvalidItems);
        this.directUnavailableItems = Math.max(0L, directUnavailableItems);
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
    public long getDirectSentItems() { return directSentItems; }
    public long getDirectOfflineItems() { return directOfflineItems; }
    public long getDirectFailedItems() { return directFailedItems; }
    public long getDirectInvalidItems() { return directInvalidItems; }
    public long getDirectUnavailableItems() { return directUnavailableItems; }
}
