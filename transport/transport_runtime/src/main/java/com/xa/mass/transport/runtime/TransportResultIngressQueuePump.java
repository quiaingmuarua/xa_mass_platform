package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.TransportResultIngressHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Drains a Redis result ingress queue into the engine-local result ingress handler.
 */
public final class TransportResultIngressQueuePump {

    private static final Logger logger = LoggerFactory.getLogger(TransportResultIngressQueuePump.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final RedisTransportResultIngressChannel queue;
    private final TransportResultIngressHandler delegate;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    public TransportResultIngressQueuePump(RedisTransportResultIngressChannel queue,
                                           TransportResultIngressHandler delegate,
                                           RuntimeTaskExecutor executor) {
        this.queue = Objects.requireNonNull(queue, "queue");
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
                ResultIngressEntry entry = queue.poll(POLL_TIMEOUT_MILLIS);
                if (entry == null) {
                    continue;
                }
                delegate.handle(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Best-effort task result ingress queue item failed", e);
            }
        }
    }
}
