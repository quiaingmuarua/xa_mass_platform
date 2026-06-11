package com.xa.mass.transport.runtime.dispatch;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default in-process route-key dispatch handoff.
 */
public final class InMemoryRouteTargetedTaskDispatchHandoff implements RouteTargetedTaskDispatchHandoff {

    private final LinkedBlockingQueue<RouteTargetedTaskDispatchBatch> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public InMemoryRouteTargetedTaskDispatchHandoff(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public void submit(RouteTargetedTaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!running.get()) {
            throw new IllegalStateException("route-targeted task dispatch handoff is stopped");
        }
        try {
            queue.put(batch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while submitting route-targeted dispatch batch", e);
        }
    }

    @Override
    public RouteTargetedTaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
        if (!running.get() && queue.isEmpty()) {
            return null;
        }
        return queue.poll(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        running.set(false);
    }
}
