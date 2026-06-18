package com.xa.mass.transport.websocket.session;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class WebSocketSessionController implements WebSocketServerSessionHandle {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSessionController.class);

    private final WebSocketSessionStore store;
    private final WebSocketSessionEvidenceDriver evidenceDriver;
    private final WebSocketSessionRefreshLoop refreshLoop;

    public WebSocketSessionController(WebSocketSessionStore store,
                                      WebSocketSessionEvidenceDriver evidenceDriver,
                                      WebSocketSessionRefreshLoop refreshLoop) {
        this.store = store;
        this.evidenceDriver = evidenceDriver;
        this.refreshLoop = refreshLoop;
    }

    @Override
    public synchronized void addSession(String deliveryBucketId,
                                        String endpointAddress,
                                        String workerId,
                                        Channel channel) {
        WebSocketSessionStore.BindResult result =
                store.bind(deliveryBucketId, endpointAddress, workerId, channel);
        if (result.ignoredRetiredChannel()) {
            logger.debug("Ignoring retired WebSocket channel for endpointAddress={} channelId={}",
                    endpointAddress, channel.id().asShortText());
            return;
        }
        if (result.unchanged()) {
            logger.debug("Session for endpointAddress={} already exists and is active. Skipping add.",
                    endpointAddress);
            return;
        }
        WebSocketSessionRecord current = result.currentRecord();
        logger.info("Connected: endpointAddress={} workerId={} channelId={} totalRoutes={}",
                current.endpointAddress(), current.workerId(), current.sessionHandle(), store.activeConnectionCount());
        if (store.hasActiveEndpointAddress(current.endpointAddress())) {
            evidenceDriver.connected(current, "websocket connected");
        }
        WebSocketSessionRecord replaced = result.replacedWorkerRecord();
        if (replaced != null) {
            logger.warn("Existing channel for endpointAddress={} workerId={} found. Replacing session.",
                    replaced.endpointAddress(), replaced.workerId());
            evidenceDriver.disconnected(replaced, "websocket session replaced");
            closeIfActive(replaced);
        }
        refreshLoop.ensureRunning();
    }

    @Override
    public synchronized void removeSession(Channel channel) {
        WebSocketSessionStore.RemoveResult result = store.remove(channel);
        if (result.retiredChannel()) {
            logger.debug("Ignoring disconnect for retired WebSocket channel: {}", channel.id().asShortText());
            return;
        }
        WebSocketSessionRecord removed = result.removedRecord();
        if (removed == null) {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
            return;
        }
        logger.info("Disconnected: endpointAddress={} workerId={} channelId={}",
                removed.endpointAddress(), removed.workerId(), removed.sessionHandle());
        if (result.removedCurrent()) {
            evidenceDriver.disconnected(removed, "websocket disconnected");
        }
        if (store.activeConnectionCount() == 0) {
            refreshLoop.cancel();
        }
    }

    public synchronized void shutdown() {
        logger.info("Shutting down websocket session controller, closing {} endpoint connections...", store.routeCount());
        refreshLoop.cancel();
        List<WebSocketSessionRecord> records = store.clear();
        for (WebSocketSessionRecord record : records) {
            if (record.isActive()) {
                evidenceDriver.disconnected(record, "websocket adapter shutdown");
                closeIfActive(record);
            }
        }
        refreshLoop.shutdown();
        logger.info("WebSocket session controller shutdown complete.");
    }

    @Override
    public WebSocketServerSession currentSession(Channel channel) {
        WebSocketSessionRecord record = store.recordForChannel(channel);
        return record != null && record.isActive() ? WebSocketServerSession.from(record) : null;
    }

    private static void closeIfActive(WebSocketSessionRecord record) {
        if (record != null && record.isActive()) {
            record.channel().close();
        }
    }

}
