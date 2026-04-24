package com.xa.mass.gateway.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServerSessionManager implements WorkerEndpointRegistry, WorkerEndpointInspector {

    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    // workerId -> Channel
    private final Map<String, Channel> workerChannelMap = new ConcurrentHashMap<>();

    // workerId -> ChannelHandlerContext
    private final Map<String, ChannelHandlerContext> workerChannelCtxMap = new ConcurrentHashMap<>();

    // Reverse index: Channel -> workerId
    private final Map<Channel, String> channelIndex = new ConcurrentHashMap<>();
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

        logger.info("Connected: workerId={} channelId={} totalWorkers={}",
                workerId, channel.id().asShortText(), workerChannelMap.size());
        if (!wasWorkerOnline && hasActiveChannel(workerId)) {
            systemEventChannel.publishWorkerOnline(workerId, "websocket connected", null);
        }
    }

    public synchronized void removeSession(Channel channel) {
        String workerId = channelIndex.remove(channel);
        if (workerId != null) {
            boolean wasWorkerOnline = hasActiveChannel(workerId);
            if (channel.equals(workerChannelMap.get(workerId))) {
                workerChannelMap.remove(workerId);
                workerChannelCtxMap.remove(workerId);
            }

            logger.info("Disconnected: workerId={} channelId={}",
                    workerId, channel.id().asShortText());
            if (wasWorkerOnline && !hasActiveChannel(workerId)) {
                systemEventChannel.publishWorkerOffline(workerId, "websocket disconnected", null);
            }
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    @Override
    public boolean sendMessage(String workerId, String message) {
        Channel channel = workerChannelMap.get(workerId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        logger.warn("Failed to send to worker={}. Channel not found or inactive.", workerId);
        return false;
    }

    public void broadcastMessage(String message) {
        TextWebSocketFrame frame = new TextWebSocketFrame(message);
        int sentCount = 0;
        for (Channel channel : workerChannelMap.values()) {
            if (channel.isActive()) {
                channel.writeAndFlush(frame.copy());
                sentCount++;
            }
        }
        logger.debug("Broadcast message sent to {} active channels.", sentCount);
    }

    @Override
    public boolean isWorkerOnline(String workerId) {
        Channel channel = workerChannelMap.get(workerId);
        return channel != null && channel.isActive();
    }

    public int getWorkerConnectionCount() {
        return (int) channelIndex.keySet().stream().filter(Channel::isActive).count();
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
        logger.info("Session manager shutdown complete.");
    }

    public Map<String, Channel> getAllWorkerChannels() {
        return Collections.unmodifiableMap(new HashMap<>(workerChannelMap));
    }

    public Map<String, ChannelHandlerContext> getAllWorkerChannelContexts() {
        return Collections.unmodifiableMap(new HashMap<>(workerChannelCtxMap));
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
                        channel != null && channel.isActive(),
                        channel != null ? channel.id().asShortText() : null,
                        "websocket"
                )
        ));
        return snapshots;
    }
}
