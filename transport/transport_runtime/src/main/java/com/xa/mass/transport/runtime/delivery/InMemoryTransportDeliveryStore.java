package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory runtime delivery store used by embedded transport runtimes.
 */
public final class InMemoryTransportDeliveryStore implements TransportDeliveryStore {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;
    public static final int DEFAULT_MAX_ITEMS_PER_ROUTE = 10_000;
    private static final long SNAPSHOT_CACHE_WINDOW_MILLIS = 250L;

    private final ConcurrentMap<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger queuedItems = new AtomicInteger();
    private final AtomicLong enqueuedItems = new AtomicLong();
    private final AtomicLong drainedItems = new AtomicLong();
    private final AtomicLong backpressureRejectedItems = new AtomicLong();
    private final AtomicLong invalidItems = new AtomicLong();
    private final AtomicLong unavailableItems = new AtomicLong();
    private final AtomicLong shutdownClearedItems = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> backpressureRejectedItemsByDeliveryQueue = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object snapshotLock = new Object();
    private final int maxQueuedItems;
    private final int maxItemsPerRoute;
    private final LongSupplier currentTimeMillis;
    private volatile TransportDeliveryStoreStats cachedSnapshot;
    private volatile long cachedSnapshotAtMillis;

    public InMemoryTransportDeliveryStore() {
        this(DEFAULT_MAX_QUEUED_ITEMS, DEFAULT_MAX_ITEMS_PER_ROUTE);
    }

    public InMemoryTransportDeliveryStore(int maxQueuedItems) {
        this(maxQueuedItems, DEFAULT_MAX_ITEMS_PER_ROUTE, System::currentTimeMillis);
    }

    public InMemoryTransportDeliveryStore(int maxQueuedItems, int maxItemsPerRoute) {
        this(maxQueuedItems, maxItemsPerRoute, System::currentTimeMillis);
    }

    InMemoryTransportDeliveryStore(int maxQueuedItems, int maxItemsPerRoute, LongSupplier currentTimeMillis) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        if (maxItemsPerRoute <= 0) {
            throw new IllegalArgumentException("maxItemsPerRoute must be positive");
        }
        this.maxQueuedItems = maxQueuedItems;
        this.maxItemsPerRoute = maxItemsPerRoute;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public DispatchOutcome enqueue(String adapterMailboxKey, DispatchRoutingItem item) {
        String normalizedAdapterMailboxKey = normalizeMailboxKey(adapterMailboxKey);
        String normalizedSelectedWorkerId = item == null
                ? null
                : TransportDeliveryAddressing.normalizeText(item.selectedWorkerId());
        if (item == null || normalizedAdapterMailboxKey == null) {
            invalidItems.incrementAndGet();
            return DispatchOutcome.invalid(
                    item != null ? item.deliveryId() : null,
                    normalizedSelectedWorkerId,
                    item != null ? item.correlationRef() : null,
                    "adapterMailboxKey must not be blank"
            );
        }
        if (normalizedSelectedWorkerId == null) {
            invalidItems.incrementAndGet();
            return DispatchOutcome.invalid(
                    item.deliveryId(),
                    null,
                    item.correlationRef(),
                    "selectedWorkerId must not be blank"
            );
        }
        if (!running.get()) {
            unavailableItems.incrementAndGet();
            return new DispatchOutcome(
                    item.deliveryId(),
                    normalizedSelectedWorkerId,
                    item.correlationRef(),
                    DispatchOutcomeStatus.UNAVAILABLE,
                    true,
                    "delivery store is stopped",
                    System.currentTimeMillis()
            );
        }

        DispatchRoutingItem normalizedItem = normalizeItem(item, normalizedSelectedWorkerId);
        while (true) {
            BucketState bucket = bucketState(normalizedAdapterMailboxKey);
            synchronized (bucket) {
                if (buckets.get(normalizedAdapterMailboxKey) != bucket) {
                    continue;
                }
                if (!running.get()) {
                    cleanupIfEmpty(normalizedAdapterMailboxKey, bucket);
                    unavailableItems.incrementAndGet();
                    return new DispatchOutcome(
                            normalizedItem.deliveryId(),
                            normalizedItem.selectedWorkerId(),
                            normalizedItem.correlationRef(),
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "delivery store is stopped",
                            System.currentTimeMillis()
                    );
                }
                if (bucket.items.size() >= maxItemsPerRoute) {
                    rejectBackpressure(normalizedAdapterMailboxKey);
                    return DispatchOutcome.backpressure(
                            normalizedItem.deliveryId(),
                            normalizedItem.selectedWorkerId(),
                            normalizedItem.correlationRef(),
                            "delivery queue is full"
                    );
                }
                if (!reserveGlobalSlot()) {
                    cleanupIfEmpty(normalizedAdapterMailboxKey, bucket);
                    rejectBackpressure(normalizedAdapterMailboxKey);
                    return DispatchOutcome.backpressure(
                            normalizedItem.deliveryId(),
                            normalizedItem.selectedWorkerId(),
                            normalizedItem.correlationRef(),
                            "runtime delivery backlog is full"
                    );
                }
                bucket.items.addLast(normalizedItem);
                enqueuedItems.incrementAndGet();
                invalidateSnapshot();
                bucket.notifyAll();
                return DispatchOutcome.queued(
                        normalizedItem.deliveryId(),
                        normalizedItem.selectedWorkerId(),
                        normalizedItem.correlationRef()
                );
            }
        }
    }

    @Override
    public List<DispatchRoutingItem> drain(String adapterMailboxKey, String selectedWorkerId, int maxItems) {
        String normalizedAdapterMailboxKey = normalizeMailboxKey(adapterMailboxKey);
        String normalizedSelectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        if (normalizedAdapterMailboxKey == null || normalizedSelectedWorkerId == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        BucketState bucket = buckets.get(normalizedAdapterMailboxKey);
        if (bucket == null) {
            return List.of();
        }
        synchronized (bucket) {
            List<DispatchRoutingItem> drained = drainMatchingLocked(bucket, normalizedSelectedWorkerId, maxItems);
            if (!drained.isEmpty()) {
                releaseGlobalSlots(drained.size());
                drainedItems.addAndGet(drained.size());
                invalidateSnapshot();
            }
            cleanupIfEmpty(normalizedAdapterMailboxKey, bucket);
            return drained.isEmpty() ? List.of() : Collections.unmodifiableList(drained);
        }
    }

    @Override
    public TransportDeliveryPollResult poll(String adapterMailboxKey,
                                            String selectedWorkerId,
                                            int maxItems,
                                            long timeout,
                                            TimeUnit unit) throws InterruptedException {
        String normalizedAdapterMailboxKey = normalizeMailboxKey(adapterMailboxKey);
        String normalizedSelectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        if (normalizedAdapterMailboxKey == null || normalizedSelectedWorkerId == null || maxItems <= 0) {
            return TransportDeliveryPollResult.invalidRequest();
        }
        if (!running.get()) {
            return TransportDeliveryPollResult.shutdown();
        }
        if (timeout <= 0) {
            List<DispatchRoutingItem> drained = drain(normalizedAdapterMailboxKey, normalizedSelectedWorkerId, maxItems);
            return drained.isEmpty() ? TransportDeliveryPollResult.empty() : TransportDeliveryPollResult.deliveredView(drained);
        }

        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        BucketState bucket = bucketState(normalizedAdapterMailboxKey);
        synchronized (bucket) {
            bucket.waiters++;
            invalidateSnapshot();
            try {
                while (running.get() && !hasMatchingItem(bucket, normalizedSelectedWorkerId)) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return TransportDeliveryPollResult.empty();
                    }
                    bucket.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                }
                if (!running.get()) {
                    return TransportDeliveryPollResult.shutdown();
                }
                List<DispatchRoutingItem> drained = drainMatchingLocked(bucket, normalizedSelectedWorkerId, maxItems);
                if (drained.isEmpty()) {
                    return TransportDeliveryPollResult.empty();
                }
                releaseGlobalSlots(drained.size());
                drainedItems.addAndGet(drained.size());
                invalidateSnapshot();
                return TransportDeliveryPollResult.deliveredView(Collections.unmodifiableList(drained));
            } finally {
                bucket.waiters--;
                invalidateSnapshot();
                cleanupIfEmpty(normalizedAdapterMailboxKey, bucket);
            }
        }
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        TransportDeliveryStoreStats snapshot = cachedSnapshot;
        long now = currentTimeMillis.getAsLong();
        if (snapshot != null && now - cachedSnapshotAtMillis <= SNAPSHOT_CACHE_WINDOW_MILLIS) {
            return snapshot;
        }
        synchronized (snapshotLock) {
            snapshot = cachedSnapshot;
            now = currentTimeMillis.getAsLong();
            if (snapshot != null && now - cachedSnapshotAtMillis <= SNAPSHOT_CACHE_WINDOW_MILLIS) {
                return snapshot;
            }
            TransportDeliveryStoreStats refreshed = snapshot(now);
            cachedSnapshot = refreshed;
            cachedSnapshotAtMillis = now;
            return refreshed;
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int cleared = queuedItems.get();
        for (BucketState bucket : buckets.values()) {
            synchronized (bucket) {
                bucket.items.clear();
                bucket.notifyAll();
            }
        }
        buckets.clear();
        queuedItems.set(0);
        shutdownClearedItems.addAndGet(cleared);
        invalidateSnapshot();
    }

    private TransportDeliveryStoreStats snapshot(long nowMillis) {
        int queueCount = 0;
        int waiters = 0;
        long oldestCreatedAt = Long.MAX_VALUE;
        Map<String, TransportDeliveryQueueStats> queueByAdapter = new LinkedHashMap<>();
        buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String deliveryQueueKey = entry.getKey();
                    BucketState bucket = entry.getValue();
                    synchronized (bucket) {
                        if (bucket.items.isEmpty() && bucket.waiters == 0) {
                            return;
                        }
                        long oldestForBucket = oldestCreatedAt(bucket);
                        long oldestAge = oldestForBucket <= 0L ? 0L : Math.max(0L, nowMillis - oldestForBucket);
                        queueByAdapter.put(deliveryQueueKey, new TransportDeliveryQueueStats(
                                bucket.items.size(),
                                bucket.items.isEmpty() ? 0 : 1,
                                bucket.waiters,
                                oldestAge,
                                backpressureRejectedItemsByDeliveryQueue
                                        .getOrDefault(deliveryQueueKey, new AtomicLong())
                                        .get()
                        ));
                    }
                });
        for (Map.Entry<String, TransportDeliveryQueueStats> entry : queueByAdapter.entrySet()) {
            TransportDeliveryQueueStats stats = entry.getValue();
            if (stats.getQueuedItems() > 0) {
                queueCount++;
                oldestCreatedAt = Math.min(oldestCreatedAt, nowMillis - stats.getOldestQueuedAgeMillis());
            }
            waiters += stats.getWaitingPollers();
        }
        backpressureRejectedItemsByDeliveryQueue.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> queueByAdapter.computeIfAbsent(entry.getKey(), ignored -> new TransportDeliveryQueueStats(
                        0,
                        0,
                        0,
                        0L,
                        entry.getValue().get()
                )));

        long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, nowMillis - oldestCreatedAt);
        return new TransportDeliveryStoreStats(
                queuedItems.get(),
                queueCount,
                waiters,
                maxQueuedItems,
                oldestQueuedAgeMillis,
                enqueuedItems.get(),
                drainedItems.get(),
                backpressureRejectedItems.get(),
                invalidItems.get(),
                unavailableItems.get(),
                shutdownClearedItems.get(),
                queueByAdapter.isEmpty() ? Map.of() : Map.copyOf(queueByAdapter)
        );
    }

    private BucketState bucketState(String deliveryQueueKey) {
        return buckets.computeIfAbsent(deliveryQueueKey, ignored -> new BucketState());
    }

    private static List<DispatchRoutingItem> drainMatchingLocked(BucketState bucket, String selectedWorkerId, int maxItems) {
        List<DispatchRoutingItem> drained = new ArrayList<>(Math.max(1, maxItems));
        var iterator = bucket.items.iterator();
        while (iterator.hasNext() && drained.size() < maxItems) {
            DispatchRoutingItem item = iterator.next();
            if (!Objects.equals(selectedWorkerId, item.selectedWorkerId())) {
                continue;
            }
            drained.add(item);
            iterator.remove();
        }
        return drained;
    }

    private static boolean hasMatchingItem(BucketState bucket, String selectedWorkerId) {
        for (DispatchRoutingItem item : bucket.items) {
            if (Objects.equals(selectedWorkerId, item.selectedWorkerId())) {
                return true;
            }
        }
        return false;
    }

    private static long oldestCreatedAt(BucketState bucket) {
        long oldest = Long.MAX_VALUE;
        for (DispatchRoutingItem item : bucket.items) {
            oldest = Math.min(oldest, item.createdAtEpochMillis());
        }
        return oldest == Long.MAX_VALUE ? 0L : oldest;
    }

    private boolean reserveGlobalSlot() {
        while (true) {
            int current = queuedItems.get();
            if (current >= maxQueuedItems) {
                return false;
            }
            if (queuedItems.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseGlobalSlots(int count) {
        if (count <= 0) {
            return;
        }
        queuedItems.updateAndGet(current -> Math.max(0, current - count));
    }

    private void rejectBackpressure(String deliveryQueueKey) {
        backpressureRejectedItems.incrementAndGet();
        backpressureRejectedItemsByDeliveryQueue
                .computeIfAbsent(deliveryQueueKey, ignored -> new AtomicLong())
                .incrementAndGet();
        invalidateSnapshot();
    }

    private void cleanupIfEmpty(String deliveryQueueKey, BucketState bucket) {
        if (bucket.items.isEmpty() && bucket.waiters == 0 && buckets.remove(deliveryQueueKey, bucket)) {
            invalidateSnapshot();
        }
    }

    private void invalidateSnapshot() {
        cachedSnapshot = null;
        cachedSnapshotAtMillis = 0L;
    }

    private static DispatchRoutingItem normalizeItem(DispatchRoutingItem item, String normalizedSelectedWorkerId) {
        if (Objects.equals(normalizedSelectedWorkerId, item.selectedWorkerId())) {
            return item;
        }
        return new DispatchRoutingItem(
                item.deliveryId(),
                normalizedSelectedWorkerId,
                item.payload(),
                item.correlationRef(),
                item.deadlineEpochMillis(),
                item.createdAtEpochMillis()
        );
    }

    private static String normalizeMailboxKey(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }

    private static final class BucketState {
        private final ArrayDeque<DispatchRoutingItem> items = new ArrayDeque<>();
        private int waiters;
    }
}
