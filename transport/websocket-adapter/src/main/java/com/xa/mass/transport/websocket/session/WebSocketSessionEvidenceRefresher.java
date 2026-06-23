package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebSocketSessionEvidenceRefresher implements ManagedTransportAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSessionEvidenceRefresher.class);

    private final String adapterId;
    private final WebSocketSessionStore store;
    private final AdapterSessionEvidencePublisher sessionEvidencePublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> refreshFuture;

    public WebSocketSessionEvidenceRefresher(String adapterId,
                                             WebSocketSessionStore store,
                                             AdapterSessionEvidencePublisher sessionEvidencePublisher) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.store = Objects.requireNonNull(store, "store");
        this.sessionEvidencePublisher = Objects.requireNonNull(sessionEvidencePublisher, "sessionEvidencePublisher");
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-session-evidence-refresh-" + adapterId);
            thread.setDaemon(true);
            return thread;
        });
        long refreshIntervalMillis = refreshIntervalMillis();
        refreshFuture = scheduledExecutor.scheduleAtFixedRate(
                this::refreshActiveSessions,
                0L,
                refreshIntervalMillis,
                TimeUnit.MILLISECONDS
        );
        executor = scheduledExecutor;
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> future = refreshFuture;
        if (future != null) {
            future.cancel(false);
            refreshFuture = null;
        }
        ScheduledExecutorService scheduledExecutor = executor;
        executor = null;
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private long refreshIntervalMillis() {
        long leaseMillis = sessionEvidencePublisher.leaseMillis();
        long refreshIntervalMillis = leaseMillis / 3L;
        return Math.max(1_000L, refreshIntervalMillis);
    }

    private void refreshActiveSessions() {
        try {
            for (WebSocketSessionStore.SessionSnapshot session : store.activeSessionSnapshots()) {
                sessionEvidencePublisher.heartbeat(
                        session.workerId(),
                        session.deliveryBucketId(),
                        session.endpointAddress(),
                        session.sessionHandle(),
                        "websocket session keepalive",
                        session.sessionHandle()
                );
            }
        } catch (Exception e) {
            logger.warn("WebSocket session evidence refresh failed: {}", e.getMessage(), e);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
