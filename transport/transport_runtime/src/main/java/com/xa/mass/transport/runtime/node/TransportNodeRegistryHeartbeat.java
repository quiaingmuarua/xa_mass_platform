package com.xa.mass.transport.runtime.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Lifecycle helper that keeps a transport node registered while a consumer
 * process is running.
 */
public final class TransportNodeRegistryHeartbeat implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TransportNodeRegistryHeartbeat.class);

    private final TransportNodeRegistry registry;
    private final String transportNodeId;
    private final List<String> adapterIds;
    private final LongSupplier connectionCountSupplier;
    private final long intervalMillis;
    private ScheduledExecutorService scheduler;

    public TransportNodeRegistryHeartbeat(TransportNodeRegistry registry,
                                          String transportNodeId,
                                          List<String> adapterIds,
                                          LongSupplier connectionCountSupplier,
                                          long intervalMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transportNodeId = requireText(transportNodeId, "transportNodeId");
        this.adapterIds = adapterIds == null ? List.of() : List.copyOf(adapterIds);
        this.connectionCountSupplier = connectionCountSupplier != null ? connectionCountSupplier : () -> 0L;
        this.intervalMillis = Math.max(1_000L, intervalMillis);
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        registry.register(transportNodeId, adapterIds, safeConnectionCount());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "transport-node-heartbeat-" + transportNodeId);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::heartbeatSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
        try {
            registry.markOffline(transportNodeId);
        } catch (RuntimeException e) {
            logger.warn("Failed to mark transport node offline: transportNodeId={}, reason={}",
                    transportNodeId, e.getMessage());
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void heartbeatSafely() {
        try {
            registry.heartbeat(transportNodeId, adapterIds, safeConnectionCount());
        } catch (RuntimeException e) {
            logger.warn("Failed to heartbeat transport node: transportNodeId={}, reason={}",
                    transportNodeId, e.getMessage());
        }
    }

    private long safeConnectionCount() {
        try {
            return Math.max(0L, connectionCountSupplier.getAsLong());
        } catch (RuntimeException e) {
            logger.warn("Failed to sample transport node connection count: transportNodeId={}, reason={}",
                    transportNodeId, e.getMessage());
            return 0L;
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
