package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.RouteEndpointIndex;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerClaim;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerSessionManager
        implements WorkerEndpointRegistry, WorkerEndpointInspector, RawWorkerRouteEndpointRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    private final String adapterId;
    private final RouteEndpointIndex<Channel, WebSocketRouteEndpoint> routeIndex = new RouteEndpointIndex<>();
    private final java.util.concurrent.ConcurrentMap<Channel, String> deliveryBucketByChannel = new ConcurrentHashMap<>();
    private final Set<Channel> retiredChannels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeConnectionCount = new AtomicInteger();
    private final ScheduledExecutorService routeOwnerRefreshExecutor;
    private volatile TransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
    private volatile DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry =
            NoopDeliveryCommandConsumerRegistry.INSTANCE;
    private volatile String deliveryCommandConsumerKey;
    private volatile WorkerPresenceIngress workerPresenceIngress = NoopWorkerPresenceIngress.INSTANCE;
    private volatile ScheduledFuture<?> routeOwnerRefreshFuture;

    public ServerSessionManager(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
        this.routeOwnerRefreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-route-owner-refresh-" + this.adapterId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void addSession(String deliveryBucketId,
                                        String routeKey,
                                        String workerId,
                                        Channel channel,
                                        ChannelHandlerContext ctx) {
        String normalizedDeliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        if (retiredChannels.contains(channel)) {
            logger.debug("Ignoring retired WebSocket channel for routeKey={} channelId={}",
                    routeKey, channel.id().asShortText());
            return;
        }
        RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> previousForWorker =
                activeEntryForWorker(workerId, channel);
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
        deliveryBucketByChannel.put(channel, normalizedDeliveryBucketId);
        if (result.previousEntry() == null || result.previousEntry().handle() != channel) {
            activeConnectionCount.incrementAndGet();
        }
        logger.info("Connected: routeKey={} workerId={} channelId={} totalRoutes={}",
                routeKey, workerId, channel.id().asShortText(), activeConnectionCount.get());
        if (hasActiveChannel(routeKey)) {
            String channelId = channel.id().asShortText();
            String reason = "websocket connected";
            workerPresenceIngress.sessionConnected(WorkerSessionPresenceEvent.connected(
                    workerId,
                    adapterId,
                    routeKey,
                    channelId,
                    reason,
                    channelId
            ));
            TransportRouteOwnerClaim claim = routeOwnerClaim(
                    workerId,
                    normalizedDeliveryBucketId,
                    routeKey,
                    channelId,
                    reason
            );
            claimDeliveryConsumerIfCurrent(routeOwnerStore.claimRouteOwner(claim), claim);
        }
        if (previousForWorker != null) {
            logger.warn("Existing channel for routeKey={} workerId={} found. Replacing session.",
                    routeKey, workerId);
            retireReplacedSession(previousForWorker);
        }
        ensureRouteOwnerRefreshLoop();
    }

    public synchronized void removeSession(Channel channel) {
        RouteEndpointIndex.RemoveResult<Channel, WebSocketRouteEndpoint> result = routeIndex.removeByHandle(channel);
        RouteEndpointIndex.Binding binding = result.binding();
        if (binding != null) {
            String deliveryBucketId = deliveryBucketByChannel.remove(channel);
            if (result.removedCurrentRoute()) {
                activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
            }

            logger.info("Disconnected: routeKey={} workerId={} channelId={}",
                    binding.routeKey(), binding.workerId(), channel.id().asShortText());
            if (result.removedCurrentRoute()) {
                String reason = "websocket disconnected";
                String channelId = channel.id().asShortText();
                workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                        binding.workerId(),
                        adapterId,
                        binding.routeKey(),
                        channelId,
                        reason,
                        channelId
                ));
                TransportRouteOwnerClaim claim =
                        routeOwnerClaim(binding.workerId(), deliveryBucketId, binding.routeKey(), channelId, reason);
                routeOwnerStore.releaseRouteOwner(claim);
                releaseDeliveryConsumer(claim);
            }
            if (activeConnectionCount.get() == 0) {
                cancelRouteOwnerRefreshLoop();
            }
        } else {
            if (retiredChannels.remove(channel)) {
                logger.debug("Ignoring disconnect for retired WebSocket channel: {}", channel.id().asShortText());
                return;
            }
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    private boolean sendToBoundRoute(String routeKey, String workerId, String message) {
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entriesForRoute(routeKey)) {
            if (workerId != null && !workerId.equals(entry.workerId())) {
                continue;
            }
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
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
        return sendToBoundRoute(routeKey, null, message);
    }

    @Override
    public boolean sendToSelectedWorker(String adapterId, String selectedWorkerId, String message) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        String normalizedSelectedWorkerId = normalizeNullable(selectedWorkerId);
        if (normalizedSelectedWorkerId == null) {
            return false;
        }
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entriesForWorker(normalizedSelectedWorkerId)) {
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            endpoint.channel().writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        logger.warn("Failed to send to selectedWorkerId={}. Channel not found or inactive.", normalizedSelectedWorkerId);
        return false;
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

    public String getDeliveryBucketId(Channel channel) {
        return deliveryBucketByChannel.get(channel);
    }

    public Channel getChannel(String routeKey) {
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entriesForRoute(routeKey)) {
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return endpoint.channel();
            }
        }
        return null;
    }

    public ChannelHandlerContext getChannelContext(String routeKey) {
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entriesForRoute(routeKey)) {
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return endpoint.context();
            }
        }
        return null;
    }

    @Override
    public synchronized void shutdown() {
        logger.info("Shutting down session manager, closing {} route connections...", routeIndex.routeCount());
        cancelRouteOwnerRefreshLoop();
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entries()) {
            if (entry.endpoint().isActive()) {
                String reason = "websocket adapter shutdown";
                String channelId = entry.handle().id().asShortText();
                workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                        entry.workerId(),
                        adapterId,
                        entry.routeKey(),
                        channelId,
                        reason,
                        channelId
                ));
                TransportRouteOwnerClaim claim =
                        routeOwnerClaim(
                                entry.workerId(),
                                deliveryBucketByChannel.get(entry.handle()),
                                entry.routeKey(),
                                channelId,
                                reason
                        );
                routeOwnerStore.releaseRouteOwner(claim);
                releaseDeliveryConsumer(claim);
            }
            if (entry.endpoint().isActive()) {
                entry.endpoint().channel().close();
            }
        }
        routeIndex.clear();
        deliveryBucketByChannel.clear();
        activeConnectionCount.set(0);
        routeOwnerRefreshExecutor.shutdownNow();
        logger.info("Session manager shutdown complete.");
    }

    public void setRouteOwnerStore(TransportRouteOwnerStore routeOwnerStore) {
        TransportRouteOwnerStore nextStore = routeOwnerStore != null
                ? routeOwnerStore
                : new InMemoryTransportRouteOwnerStore();
        synchronized (this) {
            this.routeOwnerStore = nextStore;
            if (activeConnectionCount.get() > 0) {
                projectActiveSessionsToRouteOwner("websocket route-owner store replaced");
                rescheduleRouteOwnerRefreshLoop();
            }
        }
    }

    public void setDeliveryCommandConsumerRegistry(DeliveryCommandConsumerRegistry registry,
                                                   String queueConsumerKey) {
        synchronized (this) {
            this.deliveryCommandConsumerRegistry = registry != null
                    ? registry
                    : NoopDeliveryCommandConsumerRegistry.INSTANCE;
            this.deliveryCommandConsumerKey = requireText(queueConsumerKey, "queueConsumerKey");
            projectActiveSessionsToRouteOwner("websocket delivery consumer registry replaced");
        }
    }

    public void setWorkerPresenceIngress(WorkerPresenceIngress workerPresenceIngress) {
        this.workerPresenceIngress = workerPresenceIngress != null
                ? workerPresenceIngress
                : NoopWorkerPresenceIngress.INSTANCE;
    }

    public String getAdapterId() {
        return adapterId;
    }

    private RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> activeEntryForWorker(String workerId,
                                                                                           Channel excludedChannel) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entriesForWorker(normalizedWorkerId)) {
            if (entry.handle() == excludedChannel || !normalizedWorkerId.equals(entry.workerId())) {
                continue;
            }
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return entry;
            }
        }
        return null;
    }

    private boolean hasActiveChannel(String routeKey) {
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entriesForRoute(routeKey)) {
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
    }

    private synchronized void ensureRouteOwnerRefreshLoop() {
        if (activeConnectionCount.get() <= 0) {
            cancelRouteOwnerRefreshLoop();
            return;
        }
        if (routeOwnerRefreshFuture != null && !routeOwnerRefreshFuture.isCancelled()) {
            return;
        }
        long refreshIntervalMillis = resolveRouteOwnerRefreshIntervalMillis();
        routeOwnerRefreshFuture = routeOwnerRefreshExecutor.scheduleAtFixedRate(
                this::refreshActiveRouteOwners,
                refreshIntervalMillis,
                refreshIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private synchronized void rescheduleRouteOwnerRefreshLoop() {
        cancelRouteOwnerRefreshLoop();
        ensureRouteOwnerRefreshLoop();
    }

    private synchronized void cancelRouteOwnerRefreshLoop() {
        if (routeOwnerRefreshFuture != null) {
            routeOwnerRefreshFuture.cancel(false);
            routeOwnerRefreshFuture = null;
        }
    }

    private long resolveRouteOwnerRefreshIntervalMillis() {
        long leaseMillis = routeOwnerStore.getLeaseMillis();
        long refreshIntervalMillis = leaseMillis / 3L;
        return Math.max(1_000L, refreshIntervalMillis);
    }

    private void refreshActiveRouteOwners() {
        synchronized (this) {
            for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entries()) {
                WebSocketRouteEndpoint endpoint = entry.endpoint();
                if (endpoint == null || !endpoint.isActive()) {
                    continue;
                }
                String channelId = entry.handle().id().asShortText();
                String reason = "websocket session keepalive";
                workerPresenceIngress.sessionHeartbeat(WorkerSessionPresenceEvent.heartbeat(
                        entry.workerId(),
                        adapterId,
                        entry.routeKey(),
                        channelId,
                        reason,
                        channelId
                ));
                TransportRouteOwnerClaim claim = routeOwnerClaim(
                                entry.workerId(),
                                deliveryBucketByChannel.get(entry.handle()),
                                entry.routeKey(),
                                channelId,
                                reason
                );
                TransportRouteOwnerRecord record = routeOwnerStore.refreshHeartbeat(claim);
                if (isCurrentOwner(record, claim)) {
                    claimDeliveryConsumer(record);
                } else {
                    releaseDeliveryConsumer(claim);
                }
            }
        }
    }

    private void projectActiveSessionsToRouteOwner(String reason) {
        for (RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> entry : routeIndex.entries()) {
            WebSocketRouteEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            TransportRouteOwnerClaim claim = routeOwnerClaim(
                            entry.workerId(),
                            deliveryBucketByChannel.get(entry.handle()),
                            entry.routeKey(),
                            entry.handle().id().asShortText(),
                            reason
            );
            claimDeliveryConsumerIfCurrent(routeOwnerStore.claimRouteOwner(claim), claim);
        }
    }

    private void retireReplacedSession(RouteEndpointIndex.Entry<Channel, WebSocketRouteEndpoint> previous) {
        if (previous == null || previous.handle() == null) {
            return;
        }
        routeIndex.removeByHandle(previous.handle());
        String deliveryBucketId = deliveryBucketByChannel.remove(previous.handle());
        activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
        retiredChannels.add(previous.handle());
        String channelId = previous.handle().id().asShortText();
        String reason = "websocket session replaced";
        workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                previous.workerId(),
                adapterId,
                previous.routeKey(),
                channelId,
                reason,
                channelId
        ));
        TransportRouteOwnerClaim claim =
                routeOwnerClaim(previous.workerId(), deliveryBucketId, previous.routeKey(), channelId, reason);
        routeOwnerStore.releaseRouteOwner(claim);
        releaseDeliveryConsumer(claim);
        WebSocketRouteEndpoint endpoint = previous.endpoint();
        if (endpoint != null && endpoint.isActive()) {
            endpoint.channel().close();
        }
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private TransportRouteOwnerClaim routeOwnerClaim(String workerId,
                                                    String deliveryBucketId,
                                                    String routeKey,
                                                    String channelId,
                                                    String reason) {
        return new TransportRouteOwnerClaim(
                workerId,
                requireText(deliveryBucketId, "deliveryBucketId"),
                adapterId,
                routeKey,
                channelId,
                reason
        );
    }

    private void claimDeliveryConsumerIfCurrent(TransportRouteOwnerRecord record, TransportRouteOwnerClaim claim) {
        if (isCurrentOwner(record, claim)) {
            claimDeliveryConsumer(record);
        }
    }

    private void claimDeliveryConsumer(TransportRouteOwnerRecord record) {
        deliveryCommandConsumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                record.getDeliveryBucketId(),
                record.getWorkerId(),
                deliveryCommandConsumerKey(),
                record.getConnectionId(),
                record.getAdapterId(),
                record.getLeaseExpireAtEpochMillis()
        ));
    }

    private void releaseDeliveryConsumer(TransportRouteOwnerClaim claim) {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                claim.deliveryBucketId(),
                claim.workerId(),
                deliveryCommandConsumerKey(),
                claim.connectionId(),
                claim.adapterId(),
                0L
        ));
    }

    private String deliveryCommandConsumerKey() {
        String current = deliveryCommandConsumerKey;
        return current != null ? current : adapterId;
    }

    private static boolean isCurrentOwner(TransportRouteOwnerRecord owner, TransportRouteOwnerClaim claim) {
        return owner != null
                && claim != null
                && claim.workerId().equals(owner.getWorkerId())
                && claim.deliveryBucketId().equals(owner.getDeliveryBucketId())
                && claim.adapterId().equals(owner.getAdapterId())
                && claim.routeKey().equals(owner.getRouteKey())
                && claim.connectionId().equals(owner.getConnectionId());
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
