package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process non-blocking delivery command handoff.
 */
public final class InMemoryTransportDeliveryCommandHandoff implements TransportDeliveryCommandHandoff {

    private final LinkedBlockingQueue<DeliveryCommandBatch> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public InMemoryTransportDeliveryCommandHandoff(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public List<DispatchOutcome> offer(DeliveryCommandBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!running.get()) {
            return batch.commands().stream()
                    .map(command -> DispatchOutcome.shutdown(command, "delivery command handoff is stopped"))
                    .toList();
        }
        boolean accepted = queue.offer(batch);
        if (accepted) {
            return batch.commands().stream()
                    .map(DispatchOutcome::queued)
                    .toList();
        }
        return batch.commands().stream()
                .map(command -> DispatchOutcome.backpressure(command, "delivery command handoff queue is full"))
                .toList();
    }

    @Override
    public DeliveryCommandBatch poll(long timeoutMillis) throws InterruptedException {
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
