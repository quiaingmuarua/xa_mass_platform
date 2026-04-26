package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * In-memory runtime delivery store used by embedded transport runtimes.
 */
public final class InMemoryTransportDeliveryStore implements TransportDeliveryStore {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;

    private final ConcurrentMap<DeliveryQueueKey, DeliveryQueue> deliveryByWorker = new ConcurrentHashMap<>();
    private final AtomicInteger queuedItems = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int maxQueuedItems;
    private final LongSupplier currentTimeMillis;

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
    public DispatchOutcome enqueue(String adapterId, TaskDispatchItem item, int maxItemsPerWorker) {
        String normalizedAdapterId = normalize(adapterId);
        if (item == null || item.getWorkerId() == null || item.getWorkerId().isBlank()) {
            return DispatchOutcome.invalid(normalizedAdapterId, item, "workerId must not be blank");
        }
        if (maxItemsPerWorker <= 0) {
            return DispatchOutcome.backpressureRejected(normalizedAdapterId, item, "delivery queue capacity is exhausted");
        }
        if (!running.get()) {
            return DispatchOutcome.adapterUnavailable(normalizedAdapterId, item, "delivery store is stopped");
        }

        String workerId = item.getWorkerId().trim();
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, workerId);
        DeliveryQueue queue = deliveryByWorker.computeIfAbsent(key, ignored -> new DeliveryQueue());
        synchronized (queue) {
            if (!running.get()) {
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
                return DispatchOutcome.adapterUnavailable(normalizedAdapterId, item, "delivery store is stopped");
            }
            if (queue.items.size() >= maxItemsPerWorker) {
                return DispatchOutcome.backpressureRejected(normalizedAdapterId, item, "delivery queue is full");
            }
            if (!reserveGlobalSlot()) {
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
                return DispatchOutcome.backpressureRejected(normalizedAdapterId, item, "runtime delivery backlog is full");
            }
            queue.items.addLast(new TransportDelivery(normalizedAdapterId, workerId, item, currentTimeMillis.getAsLong()));
            queue.notifyAll();
            return DispatchOutcome.queued(normalizedAdapterId, item);
        }
    }

    @Override
    public List<TaskDispatchItem> drain(String adapterId, String workerId, int maxItems) {
        String normalizedAdapterId = normalize(adapterId);
        String normalizedWorkerId = normalizeWorkerId(workerId);
        if (normalizedAdapterId == null || normalizedWorkerId == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, normalizedWorkerId);
        DeliveryQueue queue = deliveryByWorker.get(key);
        if (queue == null) {
            return List.of();
        }

        synchronized (queue) {
            List<TaskDispatchItem> items = drainLocked(queue, maxItems);
            releaseGlobalSlots(items.size());
            if (queue.items.isEmpty() && queue.waiters == 0) {
                deliveryByWorker.remove(key, queue);
            }
            return List.copyOf(items);
        }
    }

    @Override
    public List<TaskDispatchItem> poll(String adapterId,
                                       String workerId,
                                       int maxItems,
                                       long timeout,
                                       TimeUnit unit) throws InterruptedException {
        String normalizedAdapterId = normalize(adapterId);
        String normalizedWorkerId = normalizeWorkerId(workerId);
        if (normalizedAdapterId == null || normalizedWorkerId == null || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        if (timeout <= 0) {
            return drain(normalizedAdapterId, normalizedWorkerId, maxItems);
        }
        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, normalizedWorkerId);
        DeliveryQueue queue = deliveryByWorker.computeIfAbsent(key, ignored -> new DeliveryQueue());
        synchronized (queue) {
            queue.waiters++;
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
                List<TaskDispatchItem> items = drainLocked(queue, maxItems);
                releaseGlobalSlots(items.size());
                return List.copyOf(items);
            } finally {
                queue.waiters--;
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
            }
        }
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        int waiters = 0;
        long oldestCreatedAt = Long.MAX_VALUE;
        for (DeliveryQueue queue : deliveryByWorker.values()) {
            synchronized (queue) {
                waiters += queue.waiters;
                TransportDelivery oldest = queue.items.peekFirst();
                if (oldest != null) {
                    oldestCreatedAt = Math.min(oldestCreatedAt, oldest.getCreatedAtEpochMillis());
                }
            }
        }
        long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, currentTimeMillis.getAsLong() - oldestCreatedAt);
        return new TransportDeliveryStoreStats(
                queuedItems.get(),
                deliveryByWorker.size(),
                waiters,
                maxQueuedItems,
                oldestQueuedAgeMillis
        );
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (DeliveryQueue queue : deliveryByWorker.values()) {
            synchronized (queue) {
                queue.items.clear();
                queue.notifyAll();
            }
        }
        deliveryByWorker.clear();
        queuedItems.set(0);
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

    private static List<TaskDispatchItem> drainLocked(DeliveryQueue queue, int maxItems) {
        List<TaskDispatchItem> items = new ArrayList<>(Math.max(1, maxItems));
        while (items.size() < maxItems) {
            TransportDelivery delivery = queue.items.pollFirst();
            if (delivery == null) {
                break;
            }
            items.add(delivery.getDispatchItem());
        }
        return items;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeWorkerId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record DeliveryQueueKey(String adapterId, String workerId) {
        private DeliveryQueueKey {
            Objects.requireNonNull(adapterId, "adapterId");
            Objects.requireNonNull(workerId, "workerId");
        }
    }

    private static final class DeliveryQueue {
        private final Deque<TransportDelivery> items = new ArrayDeque<>();
        private int waiters;
    }
}
