package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains a route-targeted dispatch handoff queue into a local transport
 * consumer listener.
 */
public final class RouteTargetedTaskDispatchHandoffPump {

    private static final Logger logger = LoggerFactory.getLogger(RouteTargetedTaskDispatchHandoffPump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final RouteTargetedTaskDispatchHandoff handoff;
    private final RouteTargetedTaskDispatchBatchListener listener;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public RouteTargetedTaskDispatchHandoffPump(RouteTargetedTaskDispatchHandoff handoff,
                                                RouteTargetedTaskDispatchBatchListener listener,
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
        handoff.shutdown();
        Future<?> currentDrainLoop = drainLoop;
        if (currentDrainLoop != null) {
            currentDrainLoop.cancel(true);
            drainLoop = null;
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                RouteTargetedTaskDispatchBatch batch = handoff.poll(POLL_TIMEOUT_MILLIS);
                if (batch == null) {
                    continue;
                }
                listener.onRouteTargetedTaskDispatchBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Route-targeted dispatch handoff batch failed; continuing drain loop", e);
            }
        }
    }
}
