package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchHandoff;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default in-process dispatch handoff queue.
 *
 * <p>This keeps the current embedded runtime on memory while making the
 * engine -> transport queue/store seam explicit for later Redis/MQ
 * implementations.</p>
 */
public final class InMemoryTaskDispatchHandoff implements TaskDispatchHandoff {

    private final LinkedBlockingQueue<TaskDispatchBatch> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public InMemoryTaskDispatchHandoff(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public void submit(TaskDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!running.get()) {
            throw new IllegalStateException("task dispatch handoff is stopped");
        }
        try {
            queue.put(batch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while submitting task dispatch batch", e);
        }
    }

    @Override
    public TaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
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
