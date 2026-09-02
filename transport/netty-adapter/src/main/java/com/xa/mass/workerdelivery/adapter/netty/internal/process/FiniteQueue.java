package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

/** Thread-safe process-local FIFO with a soft admission capacity. */
final class FiniteQueue<T> {

    private final int softCapacity;
    private final Object ingressGate = new Object();
    private final LinkedBlockingQueue<T> items;
    private boolean accepting = true;

    FiniteQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        long physicalCapacity = 2L * capacity - 1L;
        if (physicalCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("capacity is too large");
        }
        softCapacity = capacity;
        items = new LinkedBlockingQueue<>((int) physicalCapacity);
    }

    QueueIngressStatus ingress(List<? extends T> values) {
        Objects.requireNonNull(values, "items");
        List<T> batch = List.copyOf(values);
        if (batch.size() > softCapacity) {
            throw new IllegalArgumentException(
                    "ingress batch must not exceed capacity"
            );
        }
        synchronized (ingressGate) {
            if (!accepting) {
                return QueueIngressStatus.CLOSED;
            }
            if (batch.isEmpty()) {
                return QueueIngressStatus.ACCEPTED;
            }
            if (items.size() >= softCapacity) {
                return QueueIngressStatus.FULL;
            }
            items.addAll(batch);
            return QueueIngressStatus.ACCEPTED;
        }
    }

    List<T> takeBatch(int limit) throws InterruptedException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        ArrayList<T> batch = new ArrayList<>(limit);
        batch.add(items.take());
        if (limit > 1) {
            items.drainTo(batch, limit - 1);
        }
        return List.copyOf(batch);
    }

    void stopIngress() {
        synchronized (ingressGate) {
            accepting = false;
        }
    }

    void clear() {
        items.clear();
    }

    enum QueueIngressStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }
}
