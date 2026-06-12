package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains retryable delivery failures into an engine-owned compensation bridge.
 */
public final class TransportDeliveryFailureInboxPump {

    private static final Logger logger = LoggerFactory.getLogger(TransportDeliveryFailureInboxPump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final RedisTransportDeliveryFailureChannel inbox;
    private final TransportDeliveryFailureHandler delegate;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TransportDeliveryFailureInboxPump(RedisTransportDeliveryFailureChannel inbox,
                                             TransportDeliveryFailureHandler delegate,
                                             RuntimeTaskExecutor executor) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
                TransportDeliveryFailureEvent event = inbox.pollFailure(POLL_TIMEOUT_MILLIS);
                if (event == null) {
                    continue;
                }
                boolean handled = delegate.handle(event);
                if (!handled) {
                    logger.error("Delivery failure inbox event was not handled: deliveryId={}, selectedWorkerId={}",
                            event.outcome().getDeliveryId(), event.outcome().getSelectedWorkerId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Delivery failure inbox item failed; continuing drain loop", e);
            }
        }
    }
}
