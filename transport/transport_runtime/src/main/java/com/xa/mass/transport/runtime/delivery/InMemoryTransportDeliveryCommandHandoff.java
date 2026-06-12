package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

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
            return batch.items().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            batch.adapterId(),
                            batch.deliveryQueueKey(),
                            batch.targetTransportNodeId(),
                            item,
                            null,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "delivery command handoff is stopped"))
                    .toList();
        }
        boolean accepted = queue.offer(batch);
        if (accepted) {
            return batch.items().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            batch.adapterId(),
                            batch.deliveryQueueKey(),
                            batch.targetTransportNodeId(),
                            item,
                            null,
                            DispatchOutcomeStatus.QUEUED,
                            false,
                            null))
                    .toList();
        }
        return batch.items().stream()
                .map(item -> DispatchOutcome.fromCommand(
                        batch.adapterId(),
                        batch.deliveryQueueKey(),
                        batch.targetTransportNodeId(),
                        item,
                        null,
                        DispatchOutcomeStatus.BACKPRESSURE,
                        true,
                        "delivery command handoff queue is full"))
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
