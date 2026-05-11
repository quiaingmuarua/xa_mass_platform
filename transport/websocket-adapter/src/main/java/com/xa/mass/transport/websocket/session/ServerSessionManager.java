package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.RouteEndpointIndex;
import com.xa.mass.transport.runtime.RuntimeEventBusWorkerSystemEventChannel;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerSessionManager implements WorkerEndpointRegistry, WorkerEndpointInspector {

    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    private final String adapterId;
    private final RouteEndpointIndex<Channel, WebSocketRouteEndpoint> routeIndex = new RouteEndpointIndex<>();
    private final AtomicInteger activeConnectionCount = new AtomicInteger();
    private volatile WorkerSystemEventChannel systemEventChannel = new RuntimeEventBusWorkerSystemEventChannel();
    private volatile WorkerPresenceStore workerPresenceStore = new InMemoryWorkerPresenceStore();

    public ServerSessionManager(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized void addSession(String routeKey, String workerId, Channel channel, ChannelHandlerContext ctx) {
        boolean wasRouteOnline = hasActiveChannel(routeKey);
        RouteEndpointIndex.BindResult<Channel, WebSocketRouteEndpoint> result = routeIndex.bind(
                routeKey,
                workerId,
                channel,
                new WebSocketRouteEndpoint(channel, ctx),
                WebSocketRouteEndpoint::isActive
        );
        if (result.unchanged()) {
            logger.debug("Session for routeKey={} already exists and is active. Skipping add.", routeKey);
            return;
        }
        RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> previous = result.previousEntry();
        if (previous != null && previous.handle() != channel) {
            logger.warn("Existing channel for routeKey={} found, but new channel is different. Replacing session.", routeKey);
        }
        if (previous == null || previous.handle() != channel) {
            activeConnectionCount.incrementAndGet();
            if (previous != null) {
                activeConnectionCount.decrementAndGet();
            }
        }

        logger.info("Connected: routeKey={} workerId={} channelId={} totalRoutes={}",
                routeKey, workerId, channel.id().asShortText(), activeConnectionCount.get());
        if (!wasRouteOnline && hasActiveChannel(routeKey)) {
            workerPresenceStore.markOnline(workerId, adapterId, routeKey, channel.id().asShortText(), "websocket connected");
            systemEventChannel.publishWorkerOnline(workerId, "websocket connected", null);
        }
    }

    public synchronized void removeSession(Channel channel) {
        RouteEndpointIndex.RemoveResult<Channel, WebSocketRouteEndpoint> result = routeIndex.removeByHandle(channel);
        RouteEndpointIndex.Binding binding = result.binding();
        if (binding != null) {
            if (result.removedCurrentRoute()) {
                activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
            }

            logger.info("Disconnected: routeKey={} workerId={} channelId={}",
                    binding.routeKey(), binding.workerId(), channel.id().asShortText());
            if (result.removedCurrentRoute() && !hasActiveChannel(binding.routeKey())) {
                workerPresenceStore.markOffline(
                        binding.workerId(),
                        adapterId,
                        binding.routeKey(),
                        channel.id().asShortText(),
                        "websocket disconnected"
                );
                systemEventChannel.publishWorkerOffline(binding.workerId(), "websocket disconnected", null);
            }
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    private boolean sendToBoundRoute(String routeKey, String message) {
        WebSocketRouteEndpoint endpoint = routeIndex.endpointForRoute(routeKey);
        if (endpoint != null && endpoint.isActive()) {
            endpoint.channel().writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        logger.warn("Failed to send to routeKey={}. Channel not found or inactive.", routeKey);
        return false;
    }

    @Override
    public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return sendToBoundRoute(routeKey, message);
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return hasActiveChannel(routeKey);
    }

    public int getWorkerConnectionCount() {
        return activeConnectionCount.get();
    }

    @Override
    public int getActiveConnectionCount() {
        return getWorkerConnectionCount();
    }

    public String getWorkerId(Channel channel) {
        RouteEndpointIndex.Binding binding = routeIndex.bindingForHandle(channel);
        return binding != null ? binding.workerId() : null;
    }

    public String getRouteKey(Channel channel) {
        RouteEndpointIndex.Binding binding = routeIndex.bindingForHandle(channel);
        return binding != null ? binding.routeKey() : null;
    }

    public Channel getChannel(String routeKey) {
        WebSocketRouteEndpoint endpoint = routeIndex.endpointForRoute(routeKey);
        return endpoint != null ? endpoint.channel() : null;
    }

    public ChannelHandlerContext getChannelContext(String routeKey) {
        WebSocketRouteEndpoint endpoint = routeIndex.endpointForRoute(routeKey);
        return endpoint != null ? endpoint.context() : null;
    }

    @Override
    public synchronized void shutdown() {
        logger.info("Shutting down session manager, closing {} route connections...", routeIndex.routeCount());
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entries()) {
            if (entry.endpoint().isActive()) {
                entry.endpoint().channel().close();
            }
        }
        routeIndex.clear();
        activeConnectionCount.set(0);
        logger.info("Session manager shutdown complete.");
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public void setSystemEventChannel(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel != null
                ? systemEventChannel
                : new RuntimeEventBusWorkerSystemEventChannel();
    }

    public void setWorkerPresenceStore(WorkerPresenceStore workerPresenceStore) {
        this.workerPresenceStore = workerPresenceStore != null
                ? workerPresenceStore
                : new InMemoryWorkerPresenceStore();
    }

    public String getAdapterId() {
        return adapterId;
    }

    private boolean hasActiveChannel(String routeKey) {
        WebSocketRouteEndpoint endpoint = routeIndex.endpointForRoute(routeKey);
        return endpoint != null && endpoint.isActive();
    }

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entries()) {
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            snapshots.add(
                new WorkerEndpointSnapshot(
                        entry.routeKey(),
                        entry.workerId(),
                        endpoint != null && endpoint.isActive(),
                        endpoint != null ? endpoint.channel().id().asShortText() : null,
                        adapterId
                )
            );
        }
        return snapshots;
    }

    private record WebSocketRouteEndpoint(Channel channel, ChannelHandlerContext context) {
        boolean isActive() {
            return channel != null && channel.isActive();
        }
    }
}
