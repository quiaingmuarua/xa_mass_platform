package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
    private static final long STATS_CACHE_WINDOW_MILLIS = 250L;

    private final ConcurrentMap<DeliveryQueueKey, DeliveryQueue> deliveryByWorker = new ConcurrentHashMap<>();
    private final AtomicInteger queuedItems = new AtomicInteger();
    private final AtomicLong enqueuedItems = new AtomicLong();
    private final AtomicLong drainedItems = new AtomicLong();
    private final AtomicLong backpressureRejectedItems = new AtomicLong();
    private final AtomicLong invalidItems = new AtomicLong();
    private final AtomicLong unavailableItems = new AtomicLong();
    private final AtomicLong shutdownClearedItems = new AtomicLong();
    private final ConcurrentMap<String, AdapterCounters> adapterCounters = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object statsSnapshotLock = new Object();
    private final int maxQueuedItems;
    private final LongSupplier currentTimeMillis;
    private volatile TransportDeliveryStoreStats cachedStatsSnapshot;
    private volatile long cachedStatsAtMillis;

    public InMemoryTransportDeliveryStore() {
        this(DEFAULT_MAX_QUEUED_ITEMS);
    }

    public InMemoryTransportDeliveryStore(int maxQueuedItems) {
        this(maxQueuedItems, System::currentTimeMillis);
    }

    InMemoryTransportDeliveryStore(int maxQueuedItems, LongSupplier currentTimeMillis) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        this.maxQueuedItems = maxQueuedItems;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public DispatchOutcome enqueue(TransportDispatchEnvelope envelope, int maxItemsPerRoute) {
        String normalizedAdapterId = normalize(envelope == null ? null : envelope.getAdapterId());
        if (envelope == null || envelope.getRouteKey() == null || envelope.getRouteKey().isBlank()) {
            invalidItems.incrementAndGet();
            return DispatchOutcome.invalid(normalizedAdapterId, envelope, "routeKey must not be blank");
        }
        if (maxItemsPerRoute <= 0) {
            backpressureRejectedItems.incrementAndGet();
            adapterCounters(normalizedAdapterId).backpressureRejectedItems.incrementAndGet();
            return DispatchOutcome.backpressureRejected(normalizedAdapterId, envelope, "delivery queue capacity is exhausted");
        }
        if (!running.get()) {
            unavailableItems.incrementAndGet();
            return DispatchOutcome.adapterUnavailable(normalizedAdapterId, envelope, "delivery store is stopped");
        }

        String routeKey = envelope.getRouteKey().trim();
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, routeKey);
        DeliveryQueue queue = deliveryByWorker.computeIfAbsent(key, ignored -> new DeliveryQueue());
        synchronized (queue) {
            if (!running.get()) {
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
                unavailableItems.incrementAndGet();
                return DispatchOutcome.adapterUnavailable(normalizedAdapterId, envelope, "delivery store is stopped");
            }
            if (queue.items.size() >= maxItemsPerRoute) {
                backpressureRejectedItems.incrementAndGet();
                adapterCounters(normalizedAdapterId).backpressureRejectedItems.incrementAndGet();
                return DispatchOutcome.backpressureRejected(normalizedAdapterId, envelope, "delivery queue is full");
            }
            if (!reserveGlobalSlot()) {
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
                backpressureRejectedItems.incrementAndGet();
                adapterCounters(normalizedAdapterId).backpressureRejectedItems.incrementAndGet();
                return DispatchOutcome.backpressureRejected(normalizedAdapterId, envelope, "runtime delivery backlog is full");
            }
            queue.items.addLast(new TransportDelivery(
                    normalizedAdapterId,
                    routeKey,
                    new TransportDispatchEnvelope(
                            envelope.getDeliveryId(),
                            normalizedAdapterId,
                            routeKey,
                            envelope.getCorrelationKey(),
                            envelope.getPayload(),
                            envelope.getCreatedAtEpochMillis()
                    )
            ));
            enqueuedItems.incrementAndGet();
            invalidateStatsSnapshot();
            signalWaitingPoller(queue);
            return DispatchOutcome.queued(normalizedAdapterId, envelope);
        }
    }

    @Override
    public List<TransportDispatchEnvelope> drain(String adapterId, String routeKey, int maxItems) {
        String normalizedAdapterId = normalize(adapterId);
        String normalizedRouteKey = normalizeRouteKey(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, normalizedRouteKey);
        DeliveryQueue queue = deliveryByWorker.get(key);
        if (queue == null) {
            return List.of();
        }

        synchronized (queue) {
            List<TransportDispatchEnvelope> envelopes = drainLocked(queue, maxItems);
            releaseGlobalSlots(envelopes.size());
            drainedItems.addAndGet(envelopes.size());
            if (!envelopes.isEmpty()) {
                invalidateStatsSnapshot();
            }
            if (queue.items.isEmpty() && queue.waiters == 0) {
                deliveryByWorker.remove(key, queue);
            }
            return List.copyOf(envelopes);
        }
    }

    @Override
    public List<TransportDispatchEnvelope> poll(String adapterId,
                                       String routeKey,
                                       int maxItems,
                                       long timeout,
                                       TimeUnit unit) throws InterruptedException {
        String normalizedAdapterId = normalize(adapterId);
        String normalizedRouteKey = normalizeRouteKey(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        if (timeout <= 0) {
            return drain(normalizedAdapterId, normalizedRouteKey, maxItems);
        }
        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, normalizedRouteKey);
        DeliveryQueue queue = deliveryByWorker.computeIfAbsent(key, ignored -> new DeliveryQueue());
        synchronized (queue) {
            queue.waiters++;
            invalidateStatsSnapshot();
            try {
                while (queue.items.isEmpty() && running.get()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return List.of();
                    }
                    long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    queue.wait(remainingMillis);
                }
                if (queue.items.isEmpty()) {
                    return List.of();
                }
                List<TransportDispatchEnvelope> envelopes = drainLocked(queue, maxItems);
                releaseGlobalSlots(envelopes.size());
                drainedItems.addAndGet(envelopes.size());
                if (!envelopes.isEmpty()) {
                    invalidateStatsSnapshot();
                }
                return List.copyOf(envelopes);
            } finally {
                queue.waiters--;
                invalidateStatsSnapshot();
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
            }
        }
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        long now = currentTimeMillis.getAsLong();
        TransportDeliveryStoreStats cached = cachedStatsSnapshot;
        if (cached != null && now - cachedStatsAtMillis <= STATS_CACHE_WINDOW_MILLIS) {
            return cached;
        }
        synchronized (statsSnapshotLock) {
            cached = cachedStatsSnapshot;
            now = currentTimeMillis.getAsLong();
            if (cached != null && now - cachedStatsAtMillis <= STATS_CACHE_WINDOW_MILLIS) {
                return cached;
            }
            TransportDeliveryStoreStats refreshed = snapshotStats(now);
            cachedStatsSnapshot = refreshed;
            cachedStatsAtMillis = now;
            return refreshed;
        }
    }

    private TransportDeliveryStoreStats snapshotStats(long nowMillis) {
        int waiters = 0;
        long oldestCreatedAt = Long.MAX_VALUE;
        Map<String, MutableAdapterQueueStats> queueStatsByAdapter = new HashMap<>();
        for (Map.Entry<DeliveryQueueKey, DeliveryQueue> entry : deliveryByWorker.entrySet()) {
            DeliveryQueueKey key = entry.getKey();
            DeliveryQueue queue = entry.getValue();
            synchronized (queue) {
                waiters += queue.waiters;
                TransportDelivery oldest = queue.items.peekFirst();
                if (oldest != null) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, oldest.getCreatedAtEpochMillis());
                }
                MutableAdapterQueueStats adapterStats = queueStatsByAdapter.computeIfAbsent(
                        key.adapterId(),
                        ignored -> new MutableAdapterQueueStats()
                );
                adapterStats.queueCount++;
                adapterStats.waitingPollers += queue.waiters;
                adapterStats.queuedItems += queue.items.size();
                if (oldest != null) {
                    adapterStats.oldestCreatedAt = Math.min(adapterStats.oldestCreatedAt, oldest.getCreatedAtEpochMillis());
                }
            }
        }
        long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, nowMillis - oldestCreatedAt);
        Map<String, TransportDeliveryQueueStats> queueByAdapter = snapshotQueueByAdapter(queueStatsByAdapter, nowMillis);
        return new TransportDeliveryStoreStats(
                queuedItems.get(),
                deliveryByWorker.size(),
                waiters,
                maxQueuedItems,
                oldestQueuedAgeMillis,
                enqueuedItems.get(),
                drainedItems.get(),
                backpressureRejectedItems.get(),
                invalidItems.get(),
                unavailableItems.get(),
                shutdownClearedItems.get(),
                0L,
                0L,
                0L,
                0L,
                0L,
                queueByAdapter
        );
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int clearedItems = queuedItems.get();
        for (DeliveryQueue queue : deliveryByWorker.values()) {
            synchronized (queue) {
                queue.items.clear();
                queue.notifyAll();
            }
        }
        deliveryByWorker.clear();
        queuedItems.set(0);
        shutdownClearedItems.addAndGet(clearedItems);
        invalidateStatsSnapshot();
    }

    private void invalidateStatsSnapshot() {
        cachedStatsSnapshot = null;
        cachedStatsAtMillis = 0L;
    }

    private static void signalWaitingPoller(DeliveryQueue queue) {
        if (queue.waiters > 0) {
            queue.notify();
        }
    }

    private Map<String, TransportDeliveryQueueStats> snapshotQueueByAdapter(
            Map<String, MutableAdapterQueueStats> queueStatsByAdapter,
            long nowMillis) {
        Map<String, TransportDeliveryQueueStats> snapshot = new LinkedHashMap<>();
        adapterCounters.keySet().stream().sorted().forEach(adapterId -> {
            MutableAdapterQueueStats queueStats = queueStatsByAdapter.remove(adapterId);
            snapshot.put(adapterId, toQueueStats(queueStats, adapterCounters.get(adapterId), nowMillis));
        });
        queueStatsByAdapter.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey(), toQueueStats(entry.getValue(), null, nowMillis)));
        return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
    }

    private TransportDeliveryQueueStats toQueueStats(MutableAdapterQueueStats queueStats,
                                                     AdapterCounters counters,
                                                     long nowMillis) {
        long oldestQueuedAgeMillis = queueStats == null || queueStats.oldestCreatedAt == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, nowMillis - queueStats.oldestCreatedAt);
        long backpressureRejected = counters == null ? 0L : counters.backpressureRejectedItems.get();
        return new TransportDeliveryQueueStats(
                queueStats == null ? 0 : queueStats.queuedItems,
                queueStats == null ? 0 : queueStats.queueCount,
                queueStats == null ? 0 : queueStats.waitingPollers,
                oldestQueuedAgeMillis,
                backpressureRejected
        );
    }

    private AdapterCounters adapterCounters(String adapterId) {
        String normalizedAdapterId = adapterId == null ? "unknown" : adapterId;
        return adapterCounters.computeIfAbsent(normalizedAdapterId, ignored -> new AdapterCounters());
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

    private static List<TransportDispatchEnvelope> drainLocked(DeliveryQueue queue, int maxItems) {
        List<TransportDispatchEnvelope> items = new ArrayList<>(Math.max(1, maxItems));
        while (items.size() < maxItems) {
            TransportDelivery delivery = queue.items.pollFirst();
            if (delivery == null) {
                break;
            }
            items.add(delivery.getEnvelope());
        }
        return items;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeRouteKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record DeliveryQueueKey(String adapterId, String routeKey) {
        private DeliveryQueueKey {
            Objects.requireNonNull(adapterId, "adapterId");
            Objects.requireNonNull(routeKey, "routeKey");
        }
    }

    private static final class DeliveryQueue {
        private final Deque<TransportDelivery> items = new ArrayDeque<>();
        private int waiters;
    }

    private static final class AdapterCounters {
        private final AtomicLong backpressureRejectedItems = new AtomicLong();
    }

    private static final class MutableAdapterQueueStats {
        private int queuedItems;
        private int queueCount;
        private int waitingPollers;
        private long oldestCreatedAt = Long.MAX_VALUE;
    }
}
