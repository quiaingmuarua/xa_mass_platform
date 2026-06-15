package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains delivery command handoff batches into the local delivery listener.
 */
public final class TransportDeliveryCommandHandoffPump {

    private static final Logger logger = LoggerFactory.getLogger(TransportDeliveryCommandHandoffPump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final TransportDeliveryCommandHandoff handoff;
    private final TransportDeliveryCommandListener listener;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TransportDeliveryCommandHandoffPump(TransportDeliveryCommandHandoff handoff,
                                               TransportDeliveryCommandListener listener,
                                               RuntimeTaskExecutor executor) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        drainLoop = executor.submit(this::drainLoop);
    }

    public void stop() {
        running = false;
        Future<?> currentDrainLoop = drainLoop;
        if (currentDrainLoop != null) {
            currentDrainLoop.cancel(true);
            drainLoop = null;
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                DeliveryCommandBatch batch = handoff.poll(POLL_TIMEOUT_MILLIS);
                if (batch == null) {
                    continue;
                }
                handoff.complete(batch, listener.onDeliveryCommandBatch(batch));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Delivery command handoff item failed; continuing drain loop", e);
            }
        }
    }
}
