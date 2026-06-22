package com.xa.mass.transport.polling.delivery;

import java.util.Map;

/**
 * Polling-adapter pending delivery diagnostics snapshot.
 *
 * <p>This is concrete implementation diagnostics, not a transport-core
 * dispatch contract.
 *
 * <p>Redis-ready contract split:
 *
 * <ul>
 *   <li>hard contract fields:
 *     {@code queuedItems}, {@code queueCount}, and {@code maxQueuedItems}
 *   <li>best-effort fields:
 *     {@code queueByAdapter}, {@code waitingPollers}, {@code oldestQueuedAgeMillis},
 *     {@code enqueuedItems}, {@code drainedItems},
 *     {@code backpressureRejectedItems}, {@code invalidItems},
 *     {@code unavailableItems}, {@code shutdownClearedItems}, and the
 *     nested breakdown mirrors of those diagnostics
 * </ul>
 *
 * <p>"Best effort" here means the field should stay meaningful and monotonic
 * where applicable, but future distributed queue implementations are not
 * required to preserve the exact local-JVM waiter or snapshot timing model of
 * the current in-memory store.
 */
public final class PollingPendingDeliveryBufferStats {

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
    private final Map<String, PollingPendingDeliveryQueueStats> queueByAdapter;

    public PollingPendingDeliveryBufferStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems) {
        this(queuedItems, queueCount, waitingPollers, maxQueuedItems, 0L);
    }

    public PollingPendingDeliveryBufferStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       int maxQueuedItems,
                                       long oldestQueuedAgeMillis) {
        this(queuedItems, queueCount, waitingPollers, maxQueuedItems, oldestQueuedAgeMillis, 0L, 0L, 0L,
                0L, 0L, 0L);
    }

    public PollingPendingDeliveryBufferStats(int queuedItems,
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
        this(queuedItems, queueCount, waitingPollers, maxQueuedItems, oldestQueuedAgeMillis, enqueuedItems,
                drainedItems, backpressureRejectedItems, invalidItems, unavailableItems, shutdownClearedItems,
                Map.<String, PollingPendingDeliveryQueueStats>of());
    }

    public PollingPendingDeliveryBufferStats(int queuedItems,
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
                                       Map<String, PollingPendingDeliveryQueueStats> queueByAdapter) {
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
        this.queueByAdapter = queueByAdapter == null || queueByAdapter.isEmpty()
                ? Map.of()
                : Map.copyOf(queueByAdapter);
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

    /**
     * Queue-path only legacy breakdown.
     *
     * <p>This field keeps the old name for diagnostic API stability. It is not
     * queue ownership truth; polling buffers may aggregate under the adapter
     * mailbox instead of exposing selected-worker slots.
     */
    public Map<String, PollingPendingDeliveryQueueStats> getQueueByAdapter() {
        return queueByAdapter;
    }
}
