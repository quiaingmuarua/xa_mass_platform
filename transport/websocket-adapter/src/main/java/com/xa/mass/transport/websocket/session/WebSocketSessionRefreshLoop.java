package com.xa.mass.transport.websocket.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class WebSocketSessionRefreshLoop {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSessionRefreshLoop.class);

    private final WebSocketSessionStore store;
    private final WebSocketSessionEvidenceDriver evidenceDriver;
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> refreshFuture;

    public WebSocketSessionRefreshLoop(String adapterId,
                                       WebSocketSessionStore store,
                                       WebSocketSessionEvidenceDriver evidenceDriver) {
        this.store = store;
        this.evidenceDriver = evidenceDriver;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-endpoint-lease-refresh-" + adapterId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void ensureRunning() {
        if (store.activeConnectionCount() <= 0) {
            cancel();
            return;
        }
        if (refreshFuture != null && !refreshFuture.isCancelled()) {
            return;
        }
        long refreshIntervalMillis = refreshIntervalMillis();
        refreshFuture = executor.scheduleAtFixedRate(
                this::refreshActiveSessions,
                refreshIntervalMillis,
                refreshIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public synchronized void reschedule() {
        cancel();
        ensureRunning();
    }

    public synchronized void cancel() {
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            refreshFuture = null;
        }
    }

    public void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    private long refreshIntervalMillis() {
        long leaseMillis = evidenceDriver.getLeaseMillis();
        long refreshIntervalMillis = leaseMillis / 3L;
        return Math.max(1_000L, refreshIntervalMillis);
    }

    private void refreshActiveSessions() {
        try {
            for (WebSocketSessionStore.SessionSnapshot session : store.activeSessionSnapshots()) {
                evidenceDriver.heartbeat(session, "websocket session keepalive");
            }
        } catch (Exception e) {
            logger.warn("WebSocket session evidence refresh failed: {}", e.getMessage(), e);
        }
    }
}
