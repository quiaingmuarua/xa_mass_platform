package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter-owned session manager for raw TCP socket workers.
 */
public final class SocketSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SocketSessionManager.class);

    private final String adapterId;
    private final String adapterMailboxKey;
    private final Map<String, SessionEntry> sessionsByWorkerId = new LinkedHashMap<>();
    private final Map<String, SessionEntry> sessionsByEndpointId = new LinkedHashMap<>();
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
                                        String workerId,
                                        String endpointId,
                                        Socket socket,
                                        BufferedWriter writer) {
        String normalizedDeliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedEndpointId = requireText(endpointId, "endpointId");
        SocketWorkerEndpoint endpoint = new SocketWorkerEndpoint(
                normalizedEndpointId,
                Objects.requireNonNull(socket, "socket"),
                Objects.requireNonNull(writer, "writer")
        );

        SessionEntry existingEndpointEntry = sessionsByEndpointId.get(normalizedEndpointId);
        if (existingEndpointEntry != null
                && normalizedWorkerId.equals(existingEndpointEntry.workerId())
                && existingEndpointEntry.endpoint().isActive()) {
            logger.debug("Socket session for workerId={} already exists on endpointId={}. Skipping add.",
                    normalizedWorkerId, normalizedEndpointId);
            return;
        }

        SessionEntry replacedWorkerEntry = activeEntryForWorker(normalizedWorkerId, normalizedEndpointId);
        if (existingEndpointEntry != null) {
            removeEntry(existingEndpointEntry, true, "socket session rebound");
        }

        SessionEntry current = new SessionEntry(normalizedDeliveryBucketId, normalizedWorkerId, endpoint);
        putEntry(current);
        logger.info("Connected: workerId={} endpointId={} activeConnections={}",
                current.workerId(), current.endpointId(), getActiveConnectionCount());
        if (current.endpoint().isActive()) {
            publishConnected(current, "socket connected");
        }

        if (replacedWorkerEntry != null) {
            logger.warn("Existing socket endpoint for workerId={} found. Replacing session.",
                    replacedWorkerEntry.workerId());
            removeEntry(replacedWorkerEntry, true, "socket session replaced");
        }
    }

    public synchronized void removeSession(String endpointId) {
        String normalizedEndpointId = normalizeNullable(endpointId);
        if (normalizedEndpointId == null) {
            return;
        }
        SessionEntry entry = sessionsByEndpointId.get(normalizedEndpointId);
        if (entry == null) {
            return;
        }
        removeEntry(entry, true, "socket disconnected");
    }

    public synchronized boolean sendToWorker(String workerId, String message) {
        SessionEntry entry = activeEntryForWorker(workerId, null);
        if (entry == null) {
            return false;
        }
        try {
            entry.endpoint().send(message);
            return true;
        } catch (IOException ex) {
            logger.warn("Failed to send socket message to workerId={}, endpointId={}",
                    entry.workerId(), entry.endpointId(), ex);
            removeEntry(entry, true, "socket send failed");
            return false;
        }
    }

    public synchronized boolean hasActiveWorkerSession(String workerId) {
        return activeEntryForWorker(workerId, null) != null;
    }

    public synchronized int getActiveConnectionCount() {
        int count = 0;
        for (SessionEntry entry : sessionsByEndpointId.values()) {
            if (entry.endpoint().isActive()) {
                count++;
            }
        }
        return count;
    }

    public synchronized void shutdown() {
        List<SessionEntry> entries = new ArrayList<>(sessionsByEndpointId.values());
        sessionsByWorkerId.clear();
        sessionsByEndpointId.clear();
        for (SessionEntry entry : entries) {
            if (entry.endpoint().isActive()) {
                publishDisconnected(entry, "socket adapter shutdown");
            }
            closeQuietly(entry.endpoint());
        }
    }

    public String getAdapterId() {
        return adapterId;
    }

    public synchronized void recordHeartbeat(String workerId, String endpointId, String reason, String traceId) {
        SessionEntry current = sessionsByEndpointId.get(normalizeNullable(endpointId));
        if (current == null
                || !Objects.equals(normalizeNullable(workerId), current.workerId())
                || !current.endpoint().isActive()) {
            return;
        }
        sessionEvidencePublisher.heartbeat(
                current.workerId(),
                current.deliveryBucketId(),
                current.endpointId(),
                reason,
                traceId
        );
    }

    private void putEntry(SessionEntry entry) {
        sessionsByEndpointId.put(entry.endpointId(), entry);
        sessionsByWorkerId.put(entry.workerId(), entry);
    }

    private void removeEntry(SessionEntry entry, boolean publishPresence, String reason) {
        sessionsByEndpointId.remove(entry.endpointId(), entry);
        sessionsByWorkerId.remove(entry.workerId(), entry);
        logger.info("Disconnected: workerId={} endpointId={}", entry.workerId(), entry.endpointId());
        if (publishPresence) {
            publishDisconnected(entry, reason);
        }
        closeQuietly(entry.endpoint());
    }

    private void publishConnected(SessionEntry entry, String reason) {
        sessionEvidencePublisher.connected(
                entry.workerId(),
                entry.deliveryBucketId(),
                entry.endpointId(),
                reason,
                entry.endpointId()
        );
    }

    private void publishDisconnected(SessionEntry entry, String reason) {
        sessionEvidencePublisher.disconnected(
                entry.workerId(),
                entry.deliveryBucketId(),
                entry.endpointId(),
                reason,
                entry.endpointId()
        );
    }

    private SessionEntry activeEntryForWorker(String workerId, String excludedEndpointId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        SessionEntry entry = sessionsByWorkerId.get(normalizedWorkerId);
        if (entry == null || Objects.equals(entry.endpointId(), excludedEndpointId)) {
            return null;
        }
        return entry.endpoint().isActive() ? entry : null;
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

    private record SessionEntry(String deliveryBucketId,
                                String workerId,
                                SocketWorkerEndpoint endpoint) {
        String endpointId() {
            return endpoint.endpointId();
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
