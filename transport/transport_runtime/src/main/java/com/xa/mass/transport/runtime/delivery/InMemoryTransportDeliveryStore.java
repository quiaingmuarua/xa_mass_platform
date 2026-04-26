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

/**
 * In-memory runtime delivery store used by embedded transport runtimes.
 */
public final class InMemoryTransportDeliveryStore implements TransportDeliveryStore {

    private final ConcurrentMap<DeliveryQueueKey, DeliveryQueue> deliveryByWorker = new ConcurrentHashMap<>();

    @Override
    public DispatchOutcome enqueue(String adapterId, TaskDispatchItem item, int maxItemsPerWorker) {
        String normalizedAdapterId = normalize(adapterId);
        if (item == null || item.getWorkerId() == null || item.getWorkerId().isBlank()) {
            return DispatchOutcome.invalid(normalizedAdapterId, item, "workerId must not be blank");
        }
        if (maxItemsPerWorker <= 0) {
            return DispatchOutcome.backpressureRejected(normalizedAdapterId, item, "delivery queue capacity is exhausted");
        }

        String workerId = item.getWorkerId().trim();
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, workerId);
        DeliveryQueue queue = deliveryByWorker.computeIfAbsent(key, ignored -> new DeliveryQueue());
        synchronized (queue) {
            if (queue.items.size() >= maxItemsPerWorker) {
                return DispatchOutcome.backpressureRejected(normalizedAdapterId, item, "delivery queue is full");
            }
            queue.items.addLast(new TransportDelivery(normalizedAdapterId, workerId, item, System.currentTimeMillis()));
            queue.notifyAll();
            return DispatchOutcome.queued(normalizedAdapterId, item);
        }
    }

    @Override
    public List<TaskDispatchItem> drain(String adapterId, String workerId, int maxItems) {
        String normalizedAdapterId = normalize(adapterId);
        String normalizedWorkerId = normalizeWorkerId(workerId);
        if (normalizedAdapterId == null || normalizedWorkerId == null || maxItems <= 0) {
            return List.of();
        }
        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, normalizedWorkerId);
        DeliveryQueue queue = deliveryByWorker.get(key);
        if (queue == null) {
            return List.of();
        }

        synchronized (queue) {
            List<TaskDispatchItem> items = drainLocked(queue, maxItems);
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
        if (normalizedAdapterId == null || normalizedWorkerId == null || maxItems <= 0) {
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
                while (queue.items.isEmpty()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return List.of();
                    }
                    long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    queue.wait(remainingMillis);
                }
                return List.copyOf(drainLocked(queue, maxItems));
            } finally {
                queue.waiters--;
                if (queue.items.isEmpty() && queue.waiters == 0) {
                    deliveryByWorker.remove(key, queue);
                }
            }
        }
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
