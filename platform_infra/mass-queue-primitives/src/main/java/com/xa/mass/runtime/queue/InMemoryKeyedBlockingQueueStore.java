package com.xa.mass.runtime.queue;

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
 * Narrow in-memory keyed blocking queue used by runtime modules that need
 * bounded FIFO queues without owning queue bookkeeping themselves.
 */
public final class InMemoryKeyedBlockingQueueStore<K, V> implements KeyedBlockingQueueStore<K, V> {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;
    private static final long SNAPSHOT_CACHE_WINDOW_MILLIS = 250L;

    private final ConcurrentMap<K, QueueState<V>> queues = new ConcurrentHashMap<>();
    private final AtomicInteger queuedItems = new AtomicInteger();
    private final AtomicLong enqueuedItems = new AtomicLong();
    private final AtomicLong drainedItems = new AtomicLong();
    private final AtomicLong backpressureRejectedItems = new AtomicLong();
    private final AtomicLong invalidItems = new AtomicLong();
    private final AtomicLong unavailableItems = new AtomicLong();
    private final AtomicLong shutdownClearedItems = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object snapshotLock = new Object();
    private final int maxQueuedItems;
    private final LongSupplier currentTimeMillis;
    private volatile KeyedQueueSnapshot<K> cachedSnapshot;
    private volatile long cachedSnapshotAtMillis;

    public InMemoryKeyedBlockingQueueStore() {
        this(DEFAULT_MAX_QUEUED_ITEMS);
    }

    public InMemoryKeyedBlockingQueueStore(int maxQueuedItems) {
        this(maxQueuedItems, System::currentTimeMillis);
    }

    public InMemoryKeyedBlockingQueueStore(int maxQueuedItems, LongSupplier currentTimeMillis) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        this.maxQueuedItems = maxQueuedItems;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public KeyedQueueOfferResult offer(K key, KeyedQueueEntry<V> entry, int maxItemsPerKey) {
        if (key == null || entry == null) {
            invalidItems.incrementAndGet();
            return KeyedQueueOfferResult.invalid("key and entry must not be null");
        }
        QueueState<V> queue = queueState(key);
        if (maxItemsPerKey <= 0) {
            backpressureRejectedItems.incrementAndGet();
            queue.backpressureRejectedItems.incrementAndGet();
            cleanupIfEmpty(key, queue);
            return KeyedQueueOfferResult.backpressureRejected("queue capacity is exhausted");
        }
        if (!running.get()) {
            unavailableItems.incrementAndGet();
            cleanupIfEmpty(key, queue);
            return KeyedQueueOfferResult.unavailable("queue store is stopped");
        }
        synchronized (queue) {
            if (!running.get()) {
                cleanupIfEmpty(key, queue);
                unavailableItems.incrementAndGet();
                return KeyedQueueOfferResult.unavailable("queue store is stopped");
            }
            if (queue.items.size() >= maxItemsPerKey) {
                backpressureRejectedItems.incrementAndGet();
                queue.backpressureRejectedItems.incrementAndGet();
                return KeyedQueueOfferResult.backpressureRejected("queue is full");
            }
            if (!reserveGlobalSlot()) {
                cleanupIfEmpty(key, queue);
                backpressureRejectedItems.incrementAndGet();
                queue.backpressureRejectedItems.incrementAndGet();
                return KeyedQueueOfferResult.backpressureRejected("runtime backlog is full");
            }
            queue.items.addLast(entry);
            enqueuedItems.incrementAndGet();
            invalidateSnapshot();
            signalWaitingPoller(queue);
            return KeyedQueueOfferResult.enqueued();
        }
    }

    @Override
    public List<KeyedQueueEntry<V>> drain(K key, int maxItems) {
        if (key == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        QueueState<V> queue = queues.get(key);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            List<KeyedQueueEntry<V>> drained = drainLocked(queue, maxItems);
            releaseGlobalSlots(drained.size());
            drainedItems.addAndGet(drained.size());
            if (!drained.isEmpty()) {
                invalidateSnapshot();
            }
            cleanupIfEmpty(key, queue);
            return List.copyOf(drained);
        }
    }

    @Override
    public List<KeyedQueueEntry<V>> poll(K key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException {
        if (key == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        if (timeout <= 0) {
            return drain(key, maxItems);
        }
        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        QueueState<V> queue = queueState(key);
        synchronized (queue) {
            queue.waiters++;
            invalidateSnapshot();
            try {
                while (queue.items.isEmpty() && running.get()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return List.of();
                    }
                    queue.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                }
                if (queue.items.isEmpty()) {
                    return List.of();
                }
                List<KeyedQueueEntry<V>> drained = drainLocked(queue, maxItems);
                releaseGlobalSlots(drained.size());
                drainedItems.addAndGet(drained.size());
                if (!drained.isEmpty()) {
                    invalidateSnapshot();
                }
                return List.copyOf(drained);
            } finally {
                queue.waiters--;
                invalidateSnapshot();
                cleanupIfEmpty(key, queue);
            }
        }
    }

    @Override
    public KeyedQueueSnapshot<K> snapshot() {
        long now = currentTimeMillis.getAsLong();
        KeyedQueueSnapshot<K> cached = cachedSnapshot;
        if (cached != null && now - cachedSnapshotAtMillis <= SNAPSHOT_CACHE_WINDOW_MILLIS) {
            return cached;
        }
        synchronized (snapshotLock) {
            cached = cachedSnapshot;
            now = currentTimeMillis.getAsLong();
            if (cached != null && now - cachedSnapshotAtMillis <= SNAPSHOT_CACHE_WINDOW_MILLIS) {
                return cached;
            }
            KeyedQueueSnapshot<K> refreshed = snapshot(now);
            cachedSnapshot = refreshed;
            cachedSnapshotAtMillis = now;
            return refreshed;
        }
    }

    private KeyedQueueSnapshot<K> snapshot(long nowMillis) {
        int waiters = 0;
        long oldestCreatedAt = Long.MAX_VALUE;
        Map<K, KeyedQueueKeySnapshot> queueByKey = new LinkedHashMap<>();
        for (Map.Entry<K, QueueState<V>> entry : queues.entrySet()) {
            K key = entry.getKey();
            QueueState<V> queue = entry.getValue();
            synchronized (queue) {
                KeyedQueueEntry<V> oldest = queue.items.peekFirst();
                if (oldest != null) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, oldest.createdAtEpochMillis());
                }
                waiters += queue.waiters;
                int queuedForKey = queue.items.size();
                long oldestQueuedAgeMillis = oldest == null
                        ? 0L
                        : Math.max(0L, nowMillis - oldest.createdAtEpochMillis());
                queueByKey.put(key, new KeyedQueueKeySnapshot(
                        queuedForKey,
                        queue.waiters,
                        oldestQueuedAgeMillis,
                        queue.backpressureRejectedItems.get()
                ));
            }
        }
        long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, nowMillis - oldestCreatedAt);
        return new KeyedQueueSnapshot<>(
                queuedItems.get(),
                queues.size(),
                waiters,
                maxQueuedItems,
                oldestQueuedAgeMillis,
                enqueuedItems.get(),
                drainedItems.get(),
                backpressureRejectedItems.get(),
                invalidItems.get(),
                unavailableItems.get(),
                shutdownClearedItems.get(),
                queueByKey.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(queueByKey))
        );
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int cleared = queuedItems.get();
        for (QueueState<V> queue : queues.values()) {
            synchronized (queue) {
                queue.items.clear();
                queue.notifyAll();
            }
        }
        queues.clear();
        queuedItems.set(0);
        shutdownClearedItems.addAndGet(cleared);
        invalidateSnapshot();
    }

    private QueueState<V> queueState(K key) {
        return queues.computeIfAbsent(key, ignored -> new QueueState<>());
    }

    private void cleanupIfEmpty(K key, QueueState<V> queue) {
        if (queue.items.isEmpty() && queue.waiters == 0) {
            queues.remove(key, queue);
        }
    }

    private void invalidateSnapshot() {
        cachedSnapshot = null;
        cachedSnapshotAtMillis = 0L;
    }

    private static <V> void signalWaitingPoller(QueueState<V> queue) {
        if (queue.waiters > 0) {
            queue.notify();
        }
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

    private static <V> List<KeyedQueueEntry<V>> drainLocked(QueueState<V> queue, int maxItems) {
        List<KeyedQueueEntry<V>> drained = new ArrayList<>(Math.max(1, maxItems));
        while (drained.size() < maxItems) {
            KeyedQueueEntry<V> entry = queue.items.pollFirst();
            if (entry == null) {
                break;
            }
            drained.add(entry);
        }
        return drained;
    }

    private static final class QueueState<V> {
        private final Deque<KeyedQueueEntry<V>> items = new ArrayDeque<>();
        private final AtomicLong backpressureRejectedItems = new AtomicLong();
        private int waiters;
    }
}
