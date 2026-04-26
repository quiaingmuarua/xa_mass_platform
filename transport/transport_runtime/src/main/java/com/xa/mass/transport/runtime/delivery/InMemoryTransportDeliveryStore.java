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

/**
 * In-memory runtime delivery store used by embedded transport runtimes.
 */
public final class InMemoryTransportDeliveryStore implements TransportDeliveryStore {

    private final ConcurrentMap<DeliveryQueueKey, Deque<TransportDelivery>> deliveryByWorker = new ConcurrentHashMap<>();

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
        Deque<TransportDelivery> queue = deliveryByWorker.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= maxItemsPerWorker) {
                return DispatchOutcome.backpressureRejected(normalizedAdapterId, item, "delivery queue is full");
            }
            queue.addLast(new TransportDelivery(normalizedAdapterId, workerId, item, System.currentTimeMillis()));
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
        Deque<TransportDelivery> queue = deliveryByWorker.get(key);
        if (queue == null) {
            return List.of();
        }

        List<TaskDispatchItem> items = new ArrayList<>(Math.max(1, maxItems));
        synchronized (queue) {
            while (items.size() < maxItems) {
                TransportDelivery delivery = queue.pollFirst();
                if (delivery == null) {
                    break;
                }
                items.add(delivery.getDispatchItem());
            }
            if (queue.isEmpty()) {
                deliveryByWorker.remove(key, queue);
            }
        }
        return List.copyOf(items);
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
}
