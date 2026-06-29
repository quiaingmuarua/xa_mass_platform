package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared embedded helper for one adapter-owned dispatch queue consumer.
 */
public final class AdapterDispatchQueueConsumerLoop implements AdapterDispatchQueueConsumer {

    public static final int DEFAULT_MAX_ITEMS = 64;
    public static final long DEFAULT_POLL_TIMEOUT_MILLIS = 250L;

    private static final Logger logger = LoggerFactory.getLogger(AdapterDispatchQueueConsumerLoop.class);

    private final String dispatchQueueKey;
    private final TransportDispatchQueue dispatchQueue;
    private final AdapterCommandExecutor commandExecutor;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final int maxItems;
    private final long pollTimeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Future<?> drainLoop;

    public AdapterDispatchQueueConsumerLoop(String dispatchQueueKey,
                                            TransportDispatchQueue dispatchQueue,
                                            AdapterCommandExecutor commandExecutor,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this(
                dispatchQueueKey,
                dispatchQueue,
                commandExecutor,
                runtimeTaskExecutor,
                DEFAULT_MAX_ITEMS,
                DEFAULT_POLL_TIMEOUT_MILLIS
        );
    }

    public AdapterDispatchQueueConsumerLoop(String dispatchQueueKey,
                                            TransportDispatchQueue dispatchQueue,
                                            AdapterCommandExecutor commandExecutor,
                                            RuntimeTaskExecutor runtimeTaskExecutor,
                                            int maxItems,
                                            long pollTimeoutMillis) {
        this.dispatchQueueKey = requireText(dispatchQueueKey, "dispatchQueueKey");
        this.dispatchQueue = Objects.requireNonNull(dispatchQueue, "dispatchQueue");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.runtimeTaskExecutor = Objects.requireNonNull(runtimeTaskExecutor, "runtimeTaskExecutor");
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be greater than 0");
        }
        if (pollTimeoutMillis < 0L) {
            throw new IllegalArgumentException("pollTimeoutMillis must be non-negative");
        }
        this.maxItems = maxItems;
        this.pollTimeoutMillis = pollTimeoutMillis;
    }

    @Override
    public String dispatchQueueKey() {
        return dispatchQueueKey;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        drainLoop = runtimeTaskExecutor.submit(this::drainLoop);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Future<?> current = drainLoop;
        drainLoop = null;
        if (current != null) {
            current.cancel(true);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void drainLoop() {
        while (running.get()) {
            try {
                List<DispatchMessage> items = dispatchQueue.poll(
                        dispatchQueueKey,
                        maxItems,
                        pollTimeoutMillis
                );
                if (items == null || items.isEmpty()) {
                    continue;
                }
                logRetryableFailures(dispatch(items));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                logger.error("Adapter dispatch queue consumer failed; continuing: dispatchQueueKey={}",
                        dispatchQueueKey, e);
            }
        }
    }

    private List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        try {
            List<DispatchOutcome> outcomes = commandExecutor.dispatch(List.copyOf(items));
            return outcomes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(outcomes));
        } catch (RuntimeException e) {
            logger.error("Adapter final-hop dispatch failed: dispatchQueueKey={}, items={}",
                    dispatchQueueKey, items.size(), e);
            return items.stream()
                    .map(item -> DispatchOutcomeFactory.unavailable(item, e.getMessage()))
                    .toList();
        }
    }

    private void logRetryableFailures(List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null || !outcome.isRetryable()) {
                continue;
            }
            logger.warn("Adapter dispatch produced retryable outcome after destructive poll; engine timeout remains recovery path: dispatchQueueKey={}, deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    dispatchQueueKey,
                    outcome.getDeliveryId(),
                    outcome.getSelectedWorkerId(),
                    outcome.getStatus(),
                    outcome.getReason());
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
