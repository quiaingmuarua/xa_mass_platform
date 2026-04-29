package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerSessionManager implements WorkerEndpointRegistry, WorkerEndpointInspector {

    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    // workerId -> Channel
    private final Map<String, Channel> workerChannelMap = new ConcurrentHashMap<>();

    // workerId -> ChannelHandlerContext
    private final Map<String, ChannelHandlerContext> workerChannelCtxMap = new ConcurrentHashMap<>();

    // Reverse index: Channel -> workerId
    private final Map<Channel, String> channelIndex = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnectionCount = new AtomicInteger();
    private volatile WorkerSystemEventChannel systemEventChannel = new EventBusWorkerSystemEventChannel();

    public synchronized void addSession(String workerId, Channel channel, ChannelHandlerContext ctx) {
        boolean wasWorkerOnline = hasActiveChannel(workerId);
        Channel existingChannel = workerChannelMap.get(workerId);
        if (existingChannel != null && existingChannel == channel && existingChannel.isActive()) {
            logger.debug("Session for workerId={} already exists and is active. Skipping add.", workerId);
            return;
        }
        if (existingChannel != null && existingChannel != channel) {
            logger.warn("Existing channel for workerId={} found, but new channel is different. Replacing session.", workerId);
            channelIndex.remove(existingChannel);
        }

        workerChannelMap.put(workerId, channel);
        workerChannelCtxMap.put(workerId, ctx);
        channelIndex.put(channel, workerId);
        if (existingChannel == null || existingChannel != channel) {
            activeConnectionCount.incrementAndGet();
            if (existingChannel != null) {
                activeConnectionCount.decrementAndGet();
            }
        }

        logger.info("Connected: workerId={} channelId={} totalWorkers={}",
                workerId, channel.id().asShortText(), activeConnectionCount.get());
        if (!wasWorkerOnline && hasActiveChannel(workerId)) {
            systemEventChannel.publishWorkerOnline(workerId, "websocket connected", null);
        }
    }

    public synchronized void removeSession(Channel channel) {
        String workerId = channelIndex.remove(channel);
        if (workerId != null) {
            boolean hadRegisteredSession = workerChannelMap.containsKey(workerId);
            if (channel.equals(workerChannelMap.get(workerId))) {
                workerChannelMap.remove(workerId);
                workerChannelCtxMap.remove(workerId);
                activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
            }

            logger.info("Disconnected: workerId={} channelId={}",
                    workerId, channel.id().asShortText());
            if (hadRegisteredSession && !hasActiveChannel(workerId)) {
                systemEventChannel.publishWorkerOffline(workerId, "websocket disconnected", null);
            }
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    @Override
    public boolean sendToRoute(String routeKey, String message) {
        Channel channel = workerChannelMap.get(routeKey);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        logger.warn("Failed to send to routeKey={}. Channel not found or inactive.", routeKey);
        return false;
    }

    @Override
    public boolean isRouteOnline(String routeKey) {
        Channel channel = workerChannelMap.get(routeKey);
        return channel != null && channel.isActive();
    }

    public int getWorkerConnectionCount() {
        return activeConnectionCount.get();
    }

    @Override
    public int getActiveConnectionCount() {
        return getWorkerConnectionCount();
    }

    public String getWorkerId(Channel channel) {
        return channelIndex.get(channel);
    }

    public Channel getChannel(String workerId) {
        return workerChannelMap.get(workerId);
    }

    public ChannelHandlerContext getChannelContext(String workerId) {
        return workerChannelCtxMap.get(workerId);
    }

    @Override
    public synchronized void shutdown() {
        logger.info("Shutting down session manager, closing {} worker connections...", workerChannelMap.size());
        for (Channel channel : workerChannelMap.values()) {
            if (channel.isActive()) {
                channel.close();
            }
        }
        workerChannelMap.clear();
        workerChannelCtxMap.clear();
        channelIndex.clear();
        activeConnectionCount.set(0);
        logger.info("Session manager shutdown complete.");
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public void setSystemEventChannel(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel != null ? systemEventChannel : new EventBusWorkerSystemEventChannel();
    }

    private boolean hasActiveChannel(String workerId) {
        Channel channel = workerChannelMap.get(workerId);
        return channel != null && channel.isActive();
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        workerChannelMap.forEach((workerId, channel) -> snapshots.add(
                new WorkerEndpointSnapshot(
                        workerId,
                        workerId,
                        channel != null && channel.isActive(),
                        channel != null ? channel.id().asShortText() : null,
                        "websocket"
                )
        ));
        return snapshots;
    }
}
