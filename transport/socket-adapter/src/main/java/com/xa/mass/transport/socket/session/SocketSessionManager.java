package com.xa.mass.transport.socket.session;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Adapter-owned endpoint registry for raw TCP socket workers.
 */
public final class SocketSessionManager
        implements WorkerEndpointRegistry, WorkerEndpointInspector, RawWorkerRouteEndpointRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SocketSessionManager.class);

    private final String adapterId;
    private final RouteEndpointIndex<String, SocketWorkerEndpoint> routeIndex = new RouteEndpointIndex<>();
    private final ConcurrentMap<String, String> deliveryBucketByEndpoint = new ConcurrentHashMap<>();
    private volatile TransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
    private volatile DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry =
            NoopDeliveryCommandConsumerRegistry.INSTANCE;
    private volatile String deliveryCommandConsumerKey;
    private volatile WorkerPresenceIngress workerPresenceIngress = NoopWorkerPresenceIngress.INSTANCE;

    public SocketSessionManager(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized void addSession(String deliveryBucketId,
                                        String routeKey,
                                        String workerId,
                                        String endpointId,
                                        Socket socket,
                                        BufferedWriter writer) {
        String normalizedDeliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> previousForWorker =
                activeEntryForWorker(workerId, endpointId);
        RouteEndpointIndex.BindResult<String, SocketWorkerEndpoint> result = routeIndex.bind(
                routeKey,
                workerId,
                endpointId,
                new SocketWorkerEndpoint(endpointId, socket, writer),
                SocketWorkerEndpoint::isActive
        );
        if (result.unchanged()) {
            return;
        }
        deliveryBucketByEndpoint.put(endpointId, normalizedDeliveryBucketId);
        RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> previous = result.previousEntry();
        if (previous != null && !previous.handle().equals(endpointId)) {
            closeQuietly(previous.endpoint());
        }
        logger.info("Connected: routeKey={} workerId={} endpointId={} totalRoutes={}",
                routeKey, workerId, endpointId, routeIndex.routeCount());
        if (result.currentEntry().endpoint().isActive()) {
            String reason = "socket connected";
            workerPresenceIngress.sessionConnected(WorkerSessionPresenceEvent.connected(
                    workerId,
                    adapterId,
                    routeKey,
                    endpointId,
                    reason,
                    endpointId
            ));
            TransportRouteOwnerClaim claim =
                    routeOwnerClaim(workerId, normalizedDeliveryBucketId, routeKey, endpointId, reason);
            claimDeliveryConsumerIfCurrent(routeOwnerStore.claimRouteOwner(claim), claim);
        }
        if (previousForWorker != null) {
            logger.warn("Existing socket endpoint for routeKey={} workerId={} found. Replacing session.",
                    routeKey, workerId);
            removeSession(previousForWorker.handle(), true, "socket session replaced");
        }
    }

    public synchronized void removeSession(String endpointId) {
        removeSession(endpointId, true, "socket disconnected");
    }

    private synchronized void removeSession(String endpointId, boolean publishPresence, String reason) {
        RouteEndpointIndex.RemoveResult<String, SocketWorkerEndpoint> result = routeIndex.removeByHandle(endpointId);
        RouteEndpointIndex.Binding binding = result.binding();
        if (binding == null) {
            return;
        }
        String deliveryBucketId = deliveryBucketByEndpoint.remove(endpointId);
        if (result.removedCurrentRoute()) {
            closeQuietly(result.removedEntry().endpoint());
        }

        logger.info("Disconnected: routeKey={} workerId={} endpointId={}",
                binding.routeKey(), binding.workerId(), endpointId);
        if (result.removedCurrentRoute()) {
            if (publishPresence) {
                workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                        binding.workerId(),
                        adapterId,
                        binding.routeKey(),
                        endpointId,
                        reason,
                        endpointId
                ));
            }
            TransportRouteOwnerClaim claim =
                    routeOwnerClaim(binding.workerId(), deliveryBucketId, binding.routeKey(), endpointId, reason);
            routeOwnerStore.releaseRouteOwner(claim);
            releaseDeliveryConsumer(claim);
        }
    }

    private boolean sendToBoundRoute(String routeKey, String workerId, String message) {
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entriesForRoute(routeKey)) {
            if (workerId != null && !workerId.equals(entry.workerId())) {
                continue;
            }
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            try {
                endpoint.send(message);
                return true;
            } catch (IOException ex) {
                logger.warn("Failed to send socket message to routeKey={}, endpointId={}",
                        routeKey, endpoint.endpointId(), ex);
                removeSession(endpoint.endpointId());
            }
        }
        return false;
    }

    private boolean hasActiveRoute(String routeKey) {
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entriesForRoute(routeKey)) {
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return true;
            }
        }
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
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entriesForWorker(normalizedSelectedWorkerId)) {
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            try {
                endpoint.send(message);
                return true;
            } catch (IOException ex) {
                logger.warn("Failed to send socket message to selectedWorkerId={}, endpointId={}",
                        normalizedSelectedWorkerId, endpoint.endpointId(), ex);
                removeSession(endpoint.endpointId());
            }
        }
        return false;
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return hasActiveRoute(routeKey);
    }

    @Override
    public int getActiveConnectionCount() {
        return (int) routeIndex.entries().stream()
                .map(RouteEndpointIndex.Entry::endpoint)
                .filter(SocketWorkerEndpoint::isActive)
                .count();
    }

    @Override
    public synchronized void shutdown() {
        List<RouteEndpointIndex.Entry<String, SocketWorkerEndpoint>> entries = routeIndex.entries();
        List<SocketWorkerEndpoint> endpoints = entries.stream()
                .map(RouteEndpointIndex.Entry::endpoint)
                .toList();
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : entries) {
            if (entry.endpoint().isActive()) {
                String reason = "socket adapter shutdown";
                workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                        entry.workerId(),
                        adapterId,
                        entry.routeKey(),
                        entry.handle(),
                        reason,
                        entry.handle()
                ));
                TransportRouteOwnerClaim claim =
                        routeOwnerClaim(
                                entry.workerId(),
                                deliveryBucketByEndpoint.get(entry.handle()),
                                entry.routeKey(),
                                entry.handle(),
                                reason
                        );
                routeOwnerStore.releaseRouteOwner(claim);
                releaseDeliveryConsumer(claim);
            }
        }
        routeIndex.clear();
        deliveryBucketByEndpoint.clear();
        for (SocketWorkerEndpoint endpoint : endpoints) {
            closeQuietly(endpoint);
        }
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entries()) {
            SocketWorkerEndpoint endpoint = entry.endpoint();
            snapshots.add(new WorkerEndpointSnapshot(
                    entry.routeKey(),
                    entry.workerId(),
                    endpoint != null && endpoint.isActive(),
                    endpoint != null ? endpoint.endpointId() : null,
                    adapterId
            ));
        }
        return List.copyOf(snapshots);
    }

    public String getAdapterId() {
        return adapterId;
    }

    private RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> activeEntryForWorker(String workerId,
                                                                                        String excludedEndpointId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entriesForWorker(normalizedWorkerId)) {
            if (entry.handle().equals(excludedEndpointId) || !normalizedWorkerId.equals(entry.workerId())) {
                continue;
            }
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return entry;
            }
        }
        return null;
    }

    public void setRouteOwnerStore(TransportRouteOwnerStore routeOwnerStore) {
        TransportRouteOwnerStore nextStore = routeOwnerStore != null
                ? routeOwnerStore
                : new InMemoryTransportRouteOwnerStore();
        synchronized (this) {
            this.routeOwnerStore = nextStore;
            projectActiveSessionsToRouteOwner("socket route-owner store replaced");
        }
    }

    public void setDeliveryCommandConsumerRegistry(DeliveryCommandConsumerRegistry registry,
                                                   String queueConsumerKey) {
        synchronized (this) {
            this.deliveryCommandConsumerRegistry = registry != null
                    ? registry
                    : NoopDeliveryCommandConsumerRegistry.INSTANCE;
            this.deliveryCommandConsumerKey = requireText(queueConsumerKey, "queueConsumerKey");
            projectActiveSessionsToRouteOwner("socket delivery consumer registry replaced");
        }
    }

    public void setWorkerPresenceIngress(WorkerPresenceIngress workerPresenceIngress) {
        this.workerPresenceIngress = workerPresenceIngress != null
                ? workerPresenceIngress
                : NoopWorkerPresenceIngress.INSTANCE;
    }

    public void recordHeartbeat(String routeKey, String workerId, String endpointId, String reason, String traceId) {
        RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> current = currentEntryForHandle(endpointId);
        if (current == null
                || !Objects.equals(normalizeNullable(routeKey), current.routeKey())
                || !Objects.equals(normalizeNullable(workerId), current.workerId())) {
            return;
        }
        workerPresenceIngress.sessionHeartbeat(WorkerSessionPresenceEvent.heartbeat(
                current.workerId(),
                adapterId,
                current.routeKey(),
                endpointId,
                reason,
                traceId
        ));
        TransportRouteOwnerClaim claim = routeOwnerClaim(
                current.workerId(),
                deliveryBucketByEndpoint.get(endpointId),
                current.routeKey(),
                endpointId,
                reason
        );
        TransportRouteOwnerRecord record = routeOwnerStore.refreshHeartbeat(claim);
        if (isCurrentOwner(record, claim)) {
            claimDeliveryConsumer(record);
        } else {
            releaseDeliveryConsumer(claim);
        }
    }

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
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

    private RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> currentEntryForHandle(String endpointId) {
        RouteEndpointIndex.Binding binding = routeIndex.bindingForHandle(endpointId);
        if (binding == null) {
            return null;
        }
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entriesForRoute(binding.routeKey())) {
            if (!entry.handle().equals(endpointId)) {
                continue;
            }
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint != null && endpoint.isActive()) {
                return entry;
            }
        }
        return null;
    }

    private void projectActiveSessionsToRouteOwner(String reason) {
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entries()) {
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            TransportRouteOwnerClaim claim = routeOwnerClaim(
                            entry.workerId(),
                            deliveryBucketByEndpoint.get(entry.handle()),
                            entry.routeKey(),
                            entry.handle(),
                            reason
            );
            claimDeliveryConsumerIfCurrent(routeOwnerStore.claimRouteOwner(claim), claim);
        }
    }

    private TransportRouteOwnerClaim routeOwnerClaim(String workerId,
                                                    String deliveryBucketId,
                                                    String routeKey,
                                                    String endpointId,
                                                    String reason) {
        return new TransportRouteOwnerClaim(
                workerId,
                requireText(deliveryBucketId, "deliveryBucketId"),
                adapterId,
                routeKey,
                endpointId,
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

    private void closeQuietly(SocketWorkerEndpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        try {
            endpoint.close();
        } catch (IOException ignored) {
            // Best-effort shutdown only.
        }
    }

    private record SocketWorkerEndpoint(String endpointId, Socket socket, BufferedWriter writer) {

        boolean isActive() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        void send(String message) throws IOException {
            synchronized (writer) {
                writer.write(message);
                writer.newLine();
                writer.flush();
            }
        }

        void close() throws IOException {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
