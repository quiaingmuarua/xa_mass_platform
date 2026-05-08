package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.RouteEndpointIndex;
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
public final class SocketSessionManager implements WorkerEndpointRegistry, WorkerEndpointInspector {

    private static final Logger logger = LoggerFactory.getLogger(SocketSessionManager.class);

    private final String adapterId;
    private final RouteEndpointIndex<String, SocketWorkerEndpoint> routeIndex = new RouteEndpointIndex<>();
    private volatile WorkerSystemEventChannel systemEventChannel;

    public SocketSessionManager(String adapterId, WorkerSystemEventChannel systemEventChannel) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
        this.systemEventChannel = systemEventChannel;
    }

    public synchronized void addSession(String routeKey,
                                        String workerId,
                                        String endpointId,
                                        Socket socket,
                                        BufferedWriter writer) {
        boolean wasOnline = hasActiveRoute(routeKey);
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

        logger.info("Connected: routeKey={} workerId={} endpointId={} totalRoutes={}",
                routeKey, workerId, endpointId, routeIndex.routeCount());
        if (!wasOnline && result.currentEntry().endpoint().isActive() && systemEventChannel != null) {
            systemEventChannel.publishWorkerOnline(workerId, "socket connected", null);
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
        if (result.removedCurrentRoute() && systemEventChannel != null) {
            systemEventChannel.publishWorkerOffline(binding.workerId(), "socket disconnected", null);
        }
    }

    private boolean sendToBoundRoute(String routeKey, String message) {
        SocketWorkerEndpoint endpoint = routeIndex.endpointForRoute(routeKey);
        if (endpoint == null || !endpoint.isActive()) {
            return false;
        }
        try {
            endpoint.send(message);
            return true;
        } catch (IOException ex) {
            logger.warn("Failed to send socket message to routeKey={}, endpointId={}",
                    routeKey, endpoint.endpointId(), ex);
            removeSession(endpoint.endpointId());
            return false;
        }
    }

    private boolean hasActiveRoute(String routeKey) {
        SocketWorkerEndpoint endpoint = routeIndex.endpointForRoute(routeKey);
        return endpoint != null && endpoint.isActive();
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
        List<SocketWorkerEndpoint> endpoints = routeIndex.entries().stream()
                .map(RouteEndpointIndex.Entry::endpoint)
                .toList();
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

    public void setSystemEventChannel(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel;
    }

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
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
