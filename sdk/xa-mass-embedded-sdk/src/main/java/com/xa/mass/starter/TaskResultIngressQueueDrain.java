package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.TransportResultIngressHandler;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Starter-owned drain from transport result ingress queue into engine result convergence.
 */
final class TaskResultIngressQueueDrain {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultIngressQueueDrain.class);
    private static final long POLL_TIMEOUT_MILLIS = 250L;

    private final TransportResultIngressQueue queue;
    private final String resultQueueKey;
    private final TransportResultIngressHandler delegate;
    private final RuntimeTaskExecutor executor;
    private volatile boolean running;
    private Future<?> drainLoop;

    TaskResultIngressQueueDrain(TransportResultIngressQueue queue,
                                TransportResultIngressHandler delegate,
                                RuntimeTaskExecutor executor) {
        this(queue, TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, delegate, executor);
    }

    TaskResultIngressQueueDrain(TransportResultIngressQueue queue,
                                String resultQueueKey,
                                TransportResultIngressHandler delegate,
                                RuntimeTaskExecutor executor) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.resultQueueKey = requireText(resultQueueKey, "resultQueueKey");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        drainLoop = executor.submit(this::drainLoop);
    }

    void stop() {
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
                ResultIngressEntry entry = queue.poll(resultQueueKey, POLL_TIMEOUT_MILLIS);
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

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
