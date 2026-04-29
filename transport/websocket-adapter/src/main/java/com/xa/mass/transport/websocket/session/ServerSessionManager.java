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

    // routeKey -> Channel
    private final Map<String, Channel> routeChannelMap = new ConcurrentHashMap<>();

    // routeKey -> ChannelHandlerContext
    private final Map<String, ChannelHandlerContext> routeChannelCtxMap = new ConcurrentHashMap<>();

    // Reverse index: Channel -> session binding
    private final Map<Channel, RouteSessionBinding> channelIndex = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnectionCount = new AtomicInteger();
    private volatile WorkerSystemEventChannel systemEventChannel = new EventBusWorkerSystemEventChannel();

    public synchronized void addSession(String routeKey, String workerId, Channel channel, ChannelHandlerContext ctx) {
        boolean wasRouteOnline = hasActiveChannel(routeKey);
        Channel existingChannel = routeChannelMap.get(routeKey);
        if (existingChannel != null && existingChannel == channel && existingChannel.isActive()) {
            logger.debug("Session for routeKey={} already exists and is active. Skipping add.", routeKey);
            return;
        }
        if (existingChannel != null && existingChannel != channel) {
            logger.warn("Existing channel for routeKey={} found, but new channel is different. Replacing session.", routeKey);
            channelIndex.remove(existingChannel);
        }

        routeChannelMap.put(routeKey, channel);
        routeChannelCtxMap.put(routeKey, ctx);
        channelIndex.put(channel, new RouteSessionBinding(routeKey, workerId));
        if (existingChannel == null || existingChannel != channel) {
            activeConnectionCount.incrementAndGet();
            if (existingChannel != null) {
                activeConnectionCount.decrementAndGet();
            }
        }

        logger.info("Connected: routeKey={} workerId={} channelId={} totalRoutes={}",
                routeKey, workerId, channel.id().asShortText(), activeConnectionCount.get());
        if (!wasRouteOnline && hasActiveChannel(routeKey)) {
            systemEventChannel.publishWorkerOnline(workerId, "websocket connected", null);
        }
    }

    public synchronized void removeSession(Channel channel) {
        RouteSessionBinding binding = channelIndex.remove(channel);
        if (binding != null) {
            boolean hadRegisteredSession = routeChannelMap.containsKey(binding.routeKey());
            if (channel.equals(routeChannelMap.get(binding.routeKey()))) {
                routeChannelMap.remove(binding.routeKey());
                routeChannelCtxMap.remove(binding.routeKey());
                activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
            }

            logger.info("Disconnected: routeKey={} workerId={} channelId={}",
                    binding.routeKey(), binding.workerId(), channel.id().asShortText());
            if (hadRegisteredSession && !hasActiveChannel(binding.routeKey())) {
                systemEventChannel.publishWorkerOffline(binding.workerId(), "websocket disconnected", null);
            }
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    @Override
    public boolean sendToRoute(String routeKey, String message) {
        Channel channel = routeChannelMap.get(routeKey);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        logger.warn("Failed to send to routeKey={}. Channel not found or inactive.", routeKey);
        return false;
    }

    @Override
    public boolean isRouteOnline(String routeKey) {
        Channel channel = routeChannelMap.get(routeKey);
        return channel != null && channel.isActive();
    }

    @Override
    public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        if (adapterId != null && !"websocket".equalsIgnoreCase(adapterId.trim())) {
            return false;
        }
        return sendToRoute(routeKey, message);
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (adapterId != null && !"websocket".equalsIgnoreCase(adapterId.trim())) {
            return false;
        }
        return isRouteOnline(routeKey);
    }

    public int getWorkerConnectionCount() {
        return activeConnectionCount.get();
    }

    @Override
    public int getActiveConnectionCount() {
        return getWorkerConnectionCount();
    }

    public String getWorkerId(Channel channel) {
        RouteSessionBinding binding = channelIndex.get(channel);
        return binding != null ? binding.workerId() : null;
    }

    public Channel getChannel(String routeKey) {
        return routeChannelMap.get(routeKey);
    }

    public ChannelHandlerContext getChannelContext(String routeKey) {
        return routeChannelCtxMap.get(routeKey);
    }

    @Override
    public synchronized void shutdown() {
        logger.info("Shutting down session manager, closing {} route connections...", routeChannelMap.size());
        for (Channel channel : routeChannelMap.values()) {
            if (channel.isActive()) {
                channel.close();
            }
        }
        routeChannelMap.clear();
        routeChannelCtxMap.clear();
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

    private boolean hasActiveChannel(String routeKey) {
        Channel channel = routeChannelMap.get(routeKey);
        return channel != null && channel.isActive();
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        routeChannelMap.forEach((routeKey, channel) -> snapshots.add(
                new WorkerEndpointSnapshot(
                        routeKey,
                        resolveWorkerId(routeKey),
                        channel != null && channel.isActive(),
                        channel != null ? channel.id().asShortText() : null,
                        "websocket"
                )
        ));
        return snapshots;
    }

    private String resolveWorkerId(String routeKey) {
        for (RouteSessionBinding binding : channelIndex.values()) {
            if (routeKey.equals(binding.routeKey())) {
                return binding.workerId();
            }
        }
        return routeKey;
    }

    private record RouteSessionBinding(String routeKey, String workerId) {
    }
}
