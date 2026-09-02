package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thread-safe process-local FIFO with a soft admission capacity. */
final class FiniteQueue<T> {

    private final int capacity;
    private final ArrayDeque<T> items = new ArrayDeque<>();
    private boolean accepting = true;

    FiniteQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    QueueIngressStatus ingress(List<? extends T> values) {
        Objects.requireNonNull(values, "items");
        List<T> batch = List.copyOf(values);
        if (batch.size() > capacity) {
            throw new IllegalArgumentException(
                    "ingress batch must not exceed capacity"
            );
        }
        if (batch.isEmpty()) {
            return QueueIngressStatus.ACCEPTED;
        }
        synchronized (this) {
            if (!accepting) {
                return QueueIngressStatus.CLOSED;
            }
            if (items.size() >= capacity) {
                return QueueIngressStatus.FULL;
            }
            items.addAll(batch);
            notifyAll();
            return QueueIngressStatus.ACCEPTED;
        }
    }

    synchronized List<T> consume(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int count = Math.min(limit, items.size());
        if (count == 0) {
            return List.of();
        }
        ArrayList<T> consumed = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            consumed.add(items.removeFirst());
        }
        return List.copyOf(consumed);
    }

    synchronized List<T> awaitAndConsume(int limit)
            throws InterruptedException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        while (items.isEmpty() && accepting) {
            wait();
        }
        return consume(limit);
    }

    int capacity() {
        return capacity;
    }

    synchronized void stopIngress() {
        accepting = false;
        notifyAll();
    }

    synchronized void clear() {
        items.clear();
    }

    enum QueueIngressStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
