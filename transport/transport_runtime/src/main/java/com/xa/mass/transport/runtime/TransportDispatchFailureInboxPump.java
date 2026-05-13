package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains retryable dispatch failures into the engine-local compensation path.
 */
public final class TransportDispatchFailureInboxPump {

    private static final Logger logger = LoggerFactory.getLogger(TransportDispatchFailureInboxPump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final RedisTransportDispatchFailureChannel inbox;
    private final TransportDispatchFailureHandler delegate;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TransportDispatchFailureInboxPump(RedisTransportDispatchFailureChannel inbox,
                                             TransportDispatchFailureHandler delegate,
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
                TransportDispatchFailureEvent event = inbox.pollFailure(POLL_TIMEOUT_MILLIS);
                if (event == null) {
                    continue;
                }
                boolean compensated = delegate.compensate(event.task(), event.dispatchBindings(), event.detail());
                if (!compensated) {
                    logger.error("Dispatch failure inbox event was not compensated: taskId={}, bindings={}",
                            event.task().taskId(), event.dispatchBindings().size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Dispatch failure inbox item failed; continuing drain loop", e);
            }
        }
    }
}
