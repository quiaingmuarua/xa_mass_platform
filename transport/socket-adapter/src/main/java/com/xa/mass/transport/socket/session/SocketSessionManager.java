package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.runtime.RouteEndpointIndex;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Adapter-owned session manager for raw TCP socket workers.
 */
public final class SocketSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SocketSessionManager.class);

    private final String adapterId;
    private final String adapterMailboxKey;
    private final RouteEndpointIndex<String, SocketWorkerEndpoint> routeIndex = new RouteEndpointIndex<>();
    private final ConcurrentMap<String, String> deliveryBucketByEndpoint = new ConcurrentHashMap<>();
    private final AdapterSessionEvidencePublisher sessionEvidencePublisher;

    public SocketSessionManager(String adapterId,
                                String adapterMailboxKey,
                                AdapterSessionEvidencePublisher sessionEvidencePublisher) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        this.sessionEvidencePublisher = Objects.requireNonNull(sessionEvidencePublisher, "sessionEvidencePublisher");
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
            sessionEvidencePublisher.connected(
                    workerId,
                    normalizedDeliveryBucketId,
                    endpointId,
                    reason,
                    endpointId
            );
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
                sessionEvidencePublisher.disconnected(
                        binding.workerId(),
                        deliveryBucketId,
                        endpointId,
                        reason,
                        endpointId
                );
            }
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

    boolean sendToRoute(String routeKey, String message) {
        return sendToBoundRoute(routeKey, null, message);
    }

    public boolean sendToWorker(String workerId, String message) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return false;
        }
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : routeIndex.entriesForWorker(normalizedWorkerId)) {
            SocketWorkerEndpoint endpoint = entry.endpoint();
            if (endpoint == null || !endpoint.isActive()) {
                continue;
            }
            try {
                endpoint.send(message);
                return true;
            } catch (IOException ex) {
                logger.warn("Failed to send socket message to workerId={}, endpointId={}",
                        normalizedWorkerId, endpoint.endpointId(), ex);
                removeSession(endpoint.endpointId());
            }
        }
        return false;
    }

    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return hasActiveRoute(routeKey);
    }

    public int getActiveConnectionCount() {
        return (int) routeIndex.entries().stream()
                .map(RouteEndpointIndex.Entry::endpoint)
                .filter(SocketWorkerEndpoint::isActive)
                .count();
    }

    public synchronized void shutdown() {
        List<RouteEndpointIndex.Entry<String, SocketWorkerEndpoint>> entries = routeIndex.entries();
        List<SocketWorkerEndpoint> endpoints = entries.stream()
                .map(RouteEndpointIndex.Entry::endpoint)
                .toList();
        for (RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> entry : entries) {
            if (entry.endpoint().isActive()) {
                String reason = "socket adapter shutdown";
                sessionEvidencePublisher.disconnected(
                        entry.workerId(),
                        deliveryBucketByEndpoint.get(entry.handle()),
                        entry.handle(),
                        reason,
                        entry.handle()
                );
            }
        }
        routeIndex.clear();
        deliveryBucketByEndpoint.clear();
        for (SocketWorkerEndpoint endpoint : endpoints) {
            closeQuietly(endpoint);
        }
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

    public void recordHeartbeat(String routeKey, String workerId, String endpointId, String reason, String traceId) {
        RouteEndpointIndex.Entry<String, SocketWorkerEndpoint> current = currentEntryForHandle(endpointId);
        if (current == null
                || !Objects.equals(normalizeNullable(routeKey), current.routeKey())
                || !Objects.equals(normalizeNullable(workerId), current.workerId())) {
            return;
        }
        sessionEvidencePublisher.heartbeat(
                current.workerId(),
                deliveryBucketByEndpoint.get(endpointId),
                endpointId,
                reason,
                traceId
        );
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
