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

    private final Map<String, SocketWorkerEndpoint> endpointsByWorkerId = new ConcurrentHashMap<>();
    private final Map<String, String> workerIdByEndpointId = new ConcurrentHashMap<>();
    private volatile WorkerSystemEventChannel systemEventChannel;

    public SocketSessionManager(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel;
    }

    public synchronized void addSession(String workerId,
                                        String endpointId,
                                        Socket socket,
                                        BufferedWriter writer) {
        boolean wasOnline = isRouteOnline(workerId);
        SocketWorkerEndpoint existing = endpointsByWorkerId.get(workerId);
        if (existing != null && existing.isActive() && existing.endpointId().equals(endpointId)) {
            return;
        }
        if (existing != null && !existing.endpointId().equals(endpointId)) {
            closeQuietly(existing);
            workerIdByEndpointId.remove(existing.endpointId());
        }

        SocketWorkerEndpoint endpoint = new SocketWorkerEndpoint(endpointId, socket, writer);
        endpointsByWorkerId.put(workerId, endpoint);
        workerIdByEndpointId.put(endpointId, workerId);

        logger.info("Connected: workerId={} endpointId={} totalWorkers={}",
                workerId, endpointId, endpointsByWorkerId.size());
        if (!wasOnline && endpoint.isActive() && systemEventChannel != null) {
            systemEventChannel.publishWorkerOnline(workerId, "socket connected", null);
        }
    }

    public synchronized void removeSession(String endpointId) {
        String workerId = workerIdByEndpointId.remove(endpointId);
        if (workerId == null) {
            return;
        }
        SocketWorkerEndpoint endpoint = endpointsByWorkerId.get(workerId);
        boolean removedCurrent = endpoint != null && endpoint.endpointId().equals(endpointId);
        if (removedCurrent) {
            endpointsByWorkerId.remove(workerId);
            closeQuietly(endpoint);
        }

        logger.info("Disconnected: workerId={} endpointId={}", workerId, endpointId);
        if (removedCurrent && systemEventChannel != null) {
            systemEventChannel.publishWorkerOffline(workerId, "socket disconnected", null);
        }
    }

    @Override
    public boolean sendToRoute(String routeKey, String message) {
        SocketWorkerEndpoint endpoint = endpointsByWorkerId.get(routeKey);
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
        SocketWorkerEndpoint endpoint = endpointsByWorkerId.get(routeKey);
        return endpoint != null && endpoint.isActive();
    }

    @Override
    public int getActiveConnectionCount() {
        return (int) endpointsByWorkerId.values().stream().filter(SocketWorkerEndpoint::isActive).count();
    }

    @Override
    public synchronized void shutdown() {
        List<SocketWorkerEndpoint> endpoints = new ArrayList<>(endpointsByWorkerId.values());
        endpointsByWorkerId.clear();
        workerIdByEndpointId.clear();
        for (SocketWorkerEndpoint endpoint : endpoints) {
            closeQuietly(endpoint);
        }
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, SocketWorkerEndpoint> entry : endpointsByWorkerId.entrySet()) {
            SocketWorkerEndpoint endpoint = entry.getValue();
            snapshots.add(new WorkerEndpointSnapshot(
                    entry.getKey(),
                    entry.getKey(),
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
