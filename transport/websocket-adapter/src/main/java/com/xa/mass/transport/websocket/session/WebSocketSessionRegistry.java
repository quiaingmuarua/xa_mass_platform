package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSocketSessionRegistry implements WebSocketServerSessionHandle {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, SessionEntry> sessionsByWorkerId = new LinkedHashMap<>();
    private final Map<Channel, SessionEntry> sessionsByChannel = new LinkedHashMap<>();
    private final Set<Channel> replacedChannelsAwaitingInactive = ConcurrentHashMap.newKeySet();
    private final AdapterSessionEvidencePublisher sessionEvidencePublisher;

    public WebSocketSessionRegistry(AdapterSessionEvidencePublisher sessionEvidencePublisher) {
        this.sessionEvidencePublisher = Objects.requireNonNull(sessionEvidencePublisher, "sessionEvidencePublisher");
    }

    @Override
    public synchronized void addSession(String workerGroupId, String workerId, Channel channel) {
        bind(workerGroupId, workerId, channel);
    }

    public synchronized void bind(String workerGroupId, String workerId, Channel channel) {
        String normalizedWorkerGroupId = requireText(workerGroupId, "workerGroupId");
        String normalizedWorkerId = requireText(workerId, "workerId");
        Objects.requireNonNull(channel, "channel");
        if (replacedChannelsAwaitingInactive.contains(channel)) {
            logger.debug("Ignoring retired WebSocket channel: channelId={}", channel.id().asShortText());
            return;
        }

        SessionEntry existingChannelEntry = sessionsByChannel.get(channel);
        if (existingChannelEntry != null
                && normalizedWorkerGroupId.equals(existingChannelEntry.workerGroupId())
                && normalizedWorkerId.equals(existingChannelEntry.workerId())
                && existingChannelEntry.isActive()) {
            logger.debug("Session for workerId={} already exists on channelId={}. Skipping add.",
                    normalizedWorkerId, existingChannelEntry.sessionHandle());
            return;
        }

        SessionEntry replacedWorkerEntry = activeEntryForWorker(normalizedWorkerId, channel);
        SessionSnapshot displacedChannelSnapshot = null;
        if (existingChannelEntry != null) {
            displacedChannelSnapshot = existingChannelEntry.snapshot();
            removeEntry(existingChannelEntry, false);
        }

        SessionEntry currentEntry = new SessionEntry(
                normalizedWorkerGroupId,
                normalizedWorkerId,
                channel
        );
        putEntry(currentEntry);
        SessionSnapshot current = currentEntry.snapshot();
        logger.info("Connected: workerGroupId={} workerId={} channelId={} activeConnections={}",
                current.workerGroupId(), current.workerId(), current.sessionHandle(), activeConnectionCount());
        if (currentEntry.isActive()) {
            publishConnected(current, "websocket connected");
        }

        if (displacedChannelSnapshot != null
                && !displacedChannelSnapshot.workerId().equals(current.workerId())) {
            publishDisconnected(displacedChannelSnapshot, "websocket session rebound");
        }

        if (replacedWorkerEntry != null) {
            removeEntry(replacedWorkerEntry, true);
            replacedChannelsAwaitingInactive.add(replacedWorkerEntry.channel());
            SessionSnapshot replaced = replacedWorkerEntry.snapshot();
            logger.warn("Existing channel for workerId={} found. Replacing session.", replaced.workerId());
            publishDisconnected(replaced, "websocket session replaced");
            closeIfActive(replacedWorkerEntry.channel());
        }
    }

    @Override
    public synchronized void removeSession(Channel channel) {
        SessionEntry removed = sessionsByChannel.get(channel);
        if (removed == null) {
            boolean retired = replacedChannelsAwaitingInactive.remove(channel);
            if (retired) {
                logger.debug("Ignoring disconnect for retired WebSocket channel: {}", channel.id().asShortText());
                return;
            }
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
            return;
        }
        removeEntry(removed, true);
        SessionSnapshot snapshot = removed.snapshot();
        logger.info("Disconnected: workerGroupId={} workerId={} channelId={}",
                snapshot.workerGroupId(), snapshot.workerId(), snapshot.sessionHandle());
        publishDisconnected(snapshot, "websocket disconnected");
    }

    public synchronized void shutdown() {
        logger.info("Shutting down websocket session registry, closing {} active connections...",
                sessionsByChannel.size());
        List<SessionRef> sessions = activeSessionRefs();
        sessionsByWorkerId.clear();
        sessionsByChannel.clear();
        replacedChannelsAwaitingInactive.clear();
        for (SessionRef session : sessions) {
            publishDisconnected(session.snapshot(), "websocket adapter shutdown");
            closeIfActive(session.channel());
        }
        logger.info("WebSocket session registry shutdown complete.");
    }

    @Override
    public synchronized String currentWorkerId(Channel channel) {
        SessionEntry entry = sessionsByChannel.get(channel);
        return entry != null && entry.isActive() ? entry.workerId() : null;
    }

    public boolean sendTextToWorker(String workerId, String message) {
        Channel channel = activeChannelForWorker(workerId);
        if (channel == null) {
            return false;
        }
        channel.writeAndFlush(new TextWebSocketFrame(message));
        return true;
    }

    public synchronized List<SessionSnapshot> activeSessionSnapshots() {
        List<SessionSnapshot> sessions = new ArrayList<>();
        for (SessionEntry entry : sessionsByChannel.values()) {
            if (entry != null && entry.isActive()) {
                sessions.add(entry.snapshot());
            }
        }
        return List.copyOf(sessions);
    }

    public synchronized int activeConnectionCount() {
        int count = 0;
        for (SessionEntry entry : sessionsByChannel.values()) {
            if (entry != null && entry.isActive()) {
                count++;
            }
        }
        return count;
    }

    private synchronized Channel activeChannelForWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        SessionEntry entry = sessionsByWorkerId.get(normalizedWorkerId);
        return entry != null && entry.isActive() ? entry.channel() : null;
    }

    private List<SessionRef> activeSessionRefs() {
        List<SessionRef> refs = new ArrayList<>();
        for (SessionEntry entry : sessionsByChannel.values()) {
            if (entry != null && entry.isActive()) {
                refs.add(new SessionRef(entry.snapshot(), entry.channel()));
            }
        }
        return List.copyOf(refs);
    }

    private SessionEntry activeEntryForWorker(String workerId, Channel excludedChannel) {
        SessionEntry entry = sessionsByWorkerId.get(workerId);
        if (entry == null || entry.channel() == excludedChannel || !entry.isActive()) {
            return null;
        }
        return entry;
    }

    private void putEntry(SessionEntry entry) {
        sessionsByWorkerId.put(entry.workerId(), entry);
        sessionsByChannel.put(entry.channel(), entry);
    }

    private void removeEntry(SessionEntry entry, boolean removeChannel) {
        if (entry == null) {
            return;
        }
        SessionEntry currentWorkerEntry = sessionsByWorkerId.get(entry.workerId());
        if (currentWorkerEntry == entry) {
            sessionsByWorkerId.remove(entry.workerId());
        }
        if (removeChannel) {
            sessionsByChannel.remove(entry.channel());
        }
    }

    private void publishConnected(SessionSnapshot session, String reason) {
        sessionEvidencePublisher.connected(
                session.workerId(),
                session.workerGroupId(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
    }

    private void publishDisconnected(SessionSnapshot session, String reason) {
        sessionEvidencePublisher.disconnected(
                session.workerId(),
                session.workerGroupId(),
                session.sessionHandle(),
                reason,
                session.sessionHandle()
        );
    }

    private static void closeIfActive(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
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

    public record SessionSnapshot(String workerGroupId,
                                  String workerId,
                                  String sessionHandle) {

        public SessionSnapshot {
            workerGroupId = requireText(workerGroupId, "workerGroupId");
            workerId = requireText(workerId, "workerId");
            sessionHandle = requireText(sessionHandle, "sessionHandle");
        }
    }

    private record SessionRef(SessionSnapshot snapshot, Channel channel) {
    }

    private record SessionEntry(String workerGroupId,
                                String workerId,
                                Channel channel) {

        private SessionEntry {
            workerGroupId = requireText(workerGroupId, "workerGroupId");
            workerId = requireText(workerId, "workerId");
            Objects.requireNonNull(channel, "channel");
        }

        private String sessionHandle() {
            return channel.id().asShortText();
        }

        private boolean isActive() {
            return channel != null && channel.isActive();
        }

        private SessionSnapshot snapshot() {
            return new SessionSnapshot(workerGroupId, workerId, sessionHandle());
        }
    }
}
