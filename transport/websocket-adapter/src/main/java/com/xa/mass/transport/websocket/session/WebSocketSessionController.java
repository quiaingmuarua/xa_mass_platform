package com.xa.mass.transport.websocket.session;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
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
        WebSocketSessionStore.SessionSnapshot current = result.currentSnapshot();
        logger.info("Connected: endpointAddress={} workerId={} channelId={} totalRoutes={}",
                current.endpointAddress(), current.workerId(), current.sessionHandle(), store.activeConnectionCount());
        if (store.hasActiveEndpointAddress(current.endpointAddress())) {
            evidenceDriver.connected(current, "websocket connected");
        }
        WebSocketSessionStore.SessionSnapshot replaced = result.replacedSnapshot();
        if (replaced != null) {
            logger.warn("Existing channel for endpointAddress={} workerId={} found. Replacing session.",
                    replaced.endpointAddress(), replaced.workerId());
            evidenceDriver.disconnected(replaced, "websocket session replaced");
            closeIfActive(result.replacedChannel());
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
        WebSocketSessionStore.SessionSnapshot removed = result.removedSnapshot();
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
        List<WebSocketSessionStore.SessionRef> records = store.clear();
        for (WebSocketSessionStore.SessionRef record : records) {
            evidenceDriver.disconnected(record.snapshot(), "websocket adapter shutdown");
            closeIfActive(record.channel());
        }
        refreshLoop.shutdown();
        logger.info("WebSocket session controller shutdown complete.");
    }

    @Override
    public String currentWorkerId(Channel channel) {
        return store.workerIdForChannel(channel);
    }

    public boolean sendTextToWorker(String workerId, String message) {
        Channel channel = store.activeChannelForWorker(workerId);
        if (channel == null) {
            return false;
        }
        channel.writeAndFlush(new TextWebSocketFrame(message));
        return true;
    }

    public boolean sendTextToEndpointAddress(String endpointAddress, String message) {
        for (Channel channel : store.activeChannelsForEndpointAddress(endpointAddress)) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        return false;
    }

    public boolean hasEndpointAddress(String endpointAddress) {
        return store.hasActiveEndpointAddress(endpointAddress);
    }

    private static void closeIfActive(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

}
