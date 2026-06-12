package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.RouteEndpointIndex;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter-owned endpoint registry for raw TCP socket workers.
 */
public final class SocketSessionManager
        implements WorkerEndpointRegistry, WorkerEndpointInspector, RawWorkerRouteEndpointRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SocketSessionManager.class);

    private final String adapterId;
    private final RouteEndpointIndex<String, SocketWorkerEndpoint> routeIndex = new RouteEndpointIndex<>();
    private volatile TransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();

    public SocketSessionManager(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized void addSession(String routeKey,
                                        String workerId,
                                        String endpointId,
                                        Socket socket,
        BufferedWriter writer) {
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
        RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> previous = result.previousEntry();
        if (previous != null && !previous.handle().equals(endpointId)) {
            closeQuietly(previous.endpoint());
        }
        if (previousForWorker != null) {
            logger.warn("Existing socket endpoint for routeKey={} workerId={} found. Replacing session.",
                    routeKey, workerId);
            removeSession(previousForWorker.handle());
        }

        logger.info("Connected: routeKey={} workerId={} endpointId={} totalRoutes={}",
                routeKey, workerId, endpointId, routeIndex.routeCount());
        if (result.currentEntry().endpoint().isActive()) {
            String reason = "socket connected";
            routeOwnerStore.claimRouteOwner(workerId, adapterId, routeKey, endpointId, reason);
        }
    }

    public synchronized void removeSession(String endpointId) {
        RouteEndpointIndex.RemoveResult<String, SocketWorkerEndpoint> result = routeIndex.removeByHandle(endpointId);
        RouteEndpointIndex.Binding binding = result.binding();
        if (binding == null) {
            return;
        }
        if (result.removedCurrentRoute()) {
            closeQuietly(result.removedEntry().endpoint());
        }

        logger.info("Disconnected: routeKey={} workerId={} endpointId={}",
                binding.routeKey(), binding.workerId(), endpointId);
        if (result.removedCurrentRoute()) {
            routeOwnerStore.releaseRouteOwner(binding.workerId(), adapterId, binding.routeKey(), endpointId, "socket disconnected");
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
                routeOwnerStore.releaseRouteOwner(
                        entry.workerId(),
                        adapterId,
                        entry.routeKey(),
                        entry.handle(),
                        "socket adapter shutdown"
                );
            }
        }
        routeIndex.clear();
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

    public void recordHeartbeat(String routeKey, String workerId, String endpointId, String reason, String traceId) {
        routeOwnerStore.refreshHeartbeat(workerId, adapterId, routeKey, endpointId, reason);
    }

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void projectActiveSessionsToRouteOwner(String reason) {
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entries()) {
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            routeOwnerStore.claimRouteOwner(
                    entry.workerId(),
                    adapterId,
                    entry.routeKey(),
                    entry.handle(),
                    reason
            );
        }
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
