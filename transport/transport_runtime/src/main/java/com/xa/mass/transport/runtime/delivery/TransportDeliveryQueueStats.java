package com.xa.mass.transport.runtime.delivery;

/**
 * Per-adapter runtime delivery-store snapshot for queue-focused diagnostics.
 *
 * <p>{@code queuedItems} and {@code queueCount} are hard contract fields.
 * {@code waitingPollers}, {@code oldestQueuedAgeMillis}, and
 * {@code backpressureRejectedItems} are best-effort diagnostics that future
 * distributed queue implementations may approximate without reproducing the
 * exact local waiter model of the in-memory reference store.
 */
public final class TransportDeliveryQueueStats {

    private final int queuedItems;
    private final int queueCount;
    private final int waitingPollers;
    private final long oldestQueuedAgeMillis;
    private final long backpressureRejectedItems;

    public TransportDeliveryQueueStats(int queuedItems,
                                       int queueCount,
                                       int waitingPollers,
                                       long oldestQueuedAgeMillis,
                                       long backpressureRejectedItems) {
        this.queuedItems = Math.max(0, queuedItems);
        this.queueCount = Math.max(0, queueCount);
        this.waitingPollers = Math.max(0, waitingPollers);
        this.oldestQueuedAgeMillis = Math.max(0L, oldestQueuedAgeMillis);
        this.backpressureRejectedItems = Math.max(0L, backpressureRejectedItems);
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

    public long getOldestQueuedAgeMillis() {
        return oldestQueuedAgeMillis;
    }

    public long getBackpressureRejectedItems() {
        return backpressureRejectedItems;
    }
}
