package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter-owned endpoint registry for raw TCP socket workers.
 */
public final class SocketSessionManager implements WorkerEndpointRegistry, WorkerEndpointInspector {

    private static final Logger logger = LoggerFactory.getLogger(SocketSessionManager.class);

    private final Map<String, SocketWorkerEndpoint> endpointsByRouteKey = new ConcurrentHashMap<>();
    private final Map<String, RouteEndpointBinding> routeBindingByEndpointId = new ConcurrentHashMap<>();
    private volatile WorkerSystemEventChannel systemEventChannel;

    public SocketSessionManager(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel;
    }

    public synchronized void addSession(String routeKey,
                                        String workerId,
                                        String endpointId,
                                        Socket socket,
                                        BufferedWriter writer) {
        boolean wasOnline = isRouteOnline(routeKey);
        SocketWorkerEndpoint existing = endpointsByRouteKey.get(routeKey);
        if (existing != null && existing.isActive() && existing.endpointId().equals(endpointId)) {
            return;
        }
        if (existing != null && !existing.endpointId().equals(endpointId)) {
            closeQuietly(existing);
            routeBindingByEndpointId.remove(existing.endpointId());
        }

        SocketWorkerEndpoint endpoint = new SocketWorkerEndpoint(routeKey, workerId, endpointId, socket, writer);
        endpointsByRouteKey.put(routeKey, endpoint);
        routeBindingByEndpointId.put(endpointId, new RouteEndpointBinding(routeKey, workerId));

        logger.info("Connected: routeKey={} workerId={} endpointId={} totalRoutes={}",
                routeKey, workerId, endpointId, endpointsByRouteKey.size());
        if (!wasOnline && endpoint.isActive() && systemEventChannel != null) {
            systemEventChannel.publishWorkerOnline(workerId, "socket connected", null);
        }
    }

    public synchronized void removeSession(String endpointId) {
        RouteEndpointBinding binding = routeBindingByEndpointId.remove(endpointId);
        if (binding == null) {
            return;
        }
        SocketWorkerEndpoint endpoint = endpointsByRouteKey.get(binding.routeKey());
        boolean removedCurrent = endpoint != null && endpoint.endpointId().equals(endpointId);
        if (removedCurrent) {
            endpointsByRouteKey.remove(binding.routeKey());
            closeQuietly(endpoint);
        }

        logger.info("Disconnected: routeKey={} workerId={} endpointId={}",
                binding.routeKey(), binding.workerId(), endpointId);
        if (removedCurrent && systemEventChannel != null) {
            systemEventChannel.publishWorkerOffline(binding.workerId(), "socket disconnected", null);
        }
    }

    @Override
    public boolean sendToRoute(String routeKey, String message) {
        SocketWorkerEndpoint endpoint = endpointsByRouteKey.get(routeKey);
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

    @Override
    public boolean isRouteOnline(String routeKey) {
        SocketWorkerEndpoint endpoint = endpointsByRouteKey.get(routeKey);
        return endpoint != null && endpoint.isActive();
    }

    @Override
    public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        if (adapterId != null && !"socket".equalsIgnoreCase(adapterId.trim())) {
            return false;
        }
        return sendToRoute(routeKey, message);
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (adapterId != null && !"socket".equalsIgnoreCase(adapterId.trim())) {
            return false;
        }
        return isRouteOnline(routeKey);
    }

    @Override
    public int getActiveConnectionCount() {
        return (int) endpointsByRouteKey.values().stream().filter(SocketWorkerEndpoint::isActive).count();
    }

    @Override
    public synchronized void shutdown() {
        List<SocketWorkerEndpoint> endpoints = new ArrayList<>(endpointsByRouteKey.values());
        endpointsByRouteKey.clear();
        routeBindingByEndpointId.clear();
        for (SocketWorkerEndpoint endpoint : endpoints) {
            closeQuietly(endpoint);
        }
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, SocketWorkerEndpoint> entry : endpointsByRouteKey.entrySet()) {
            SocketWorkerEndpoint endpoint = entry.getValue();
            snapshots.add(new WorkerEndpointSnapshot(
                    entry.getKey(),
                    endpoint != null ? endpoint.workerId() : entry.getKey(),
                    endpoint != null && endpoint.isActive(),
                    endpoint != null ? endpoint.endpointId() : null,
                    "socket"
            ));
        }
        return List.copyOf(snapshots);
    }

    public void setSystemEventChannel(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel;
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

    private record RouteEndpointBinding(String routeKey, String workerId) {
    }

    private record SocketWorkerEndpoint(String routeKey, String workerId, String endpointId, Socket socket, BufferedWriter writer) {

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
