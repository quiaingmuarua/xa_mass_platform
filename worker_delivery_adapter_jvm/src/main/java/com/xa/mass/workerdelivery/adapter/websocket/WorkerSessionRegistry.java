package com.xa.mass.workerdelivery.adapter.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
@ConditionalOnProperty(
        prefix = "xa.mass.worker-delivery.adapter.websocket",
        name = "enabled",
        havingValue = "true"
)
public final class WorkerSessionRegistry {

    private static final int SEND_BUFFER_LIMIT_BYTES = 1_048_576;
    private static final CloseStatus REPLACED = new CloseStatus(
            CloseStatus.POLICY_VIOLATION.getCode(),
            "Replaced by a newer Worker session"
    );
    private final Map<String, SessionHandle> sessions =
            new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final int sendTimeLimitMillis;

    public WorkerSessionRegistry(WorkerWebSocketProperties properties) {
        sendTimeLimitMillis = Math.toIntExact(
                properties.sendTimeLimit().toMillis()
        );
    }

    public long register(String workerId, WebSocketSession session) {
        long generation = generations.incrementAndGet();
        var decorated = new ConcurrentWebSocketSessionDecorator(
                session,
                sendTimeLimitMillis,
                SEND_BUFFER_LIMIT_BYTES
        );
        SessionHandle replacement = new SessionHandle(
                generation,
                decorated
        );
        SessionHandle previous = sessions.put(workerId, replacement);
        if (previous != null) {
            close(previous.session(), REPLACED);
        }
        return generation;
    }

    public void unregister(String workerId, long generation) {
        sessions.computeIfPresent(workerId, (ignored, current) ->
                current.generation() == generation ? null : current
        );
    }

    public DeliveryAttempt send(String workerId, String encodedCommand) {
        SessionHandle current = sessions.get(workerId);
        if (current == null || !current.session().isOpen()) {
            return DeliveryAttempt.REJECTED_BEFORE_SEND;
        }
        try {
            current.session().sendMessage(new TextMessage(encodedCommand));
            return DeliveryAttempt.DELIVERED;
        } catch (IOException | RuntimeException error) {
            sessions.remove(workerId, current);
            close(current.session(), CloseStatus.SERVER_ERROR);
            return DeliveryAttempt.UNKNOWN;
        }
    }

    public void close(
            String workerId,
            long generation,
            CloseStatus status
    ) {
        SessionHandle current = sessions.get(workerId);
        if (current == null || current.generation() != generation) {
            return;
        }
        if (sessions.remove(workerId, current)) {
            close(current.session(), status);
        }
    }

    public void closeAll(CloseStatus status) {
        sessions.forEach((workerId, handle) -> {
            if (sessions.remove(workerId, handle)) {
                close(handle.session(), status);
            }
        });
    }

    int activeSessionCount() {
        return sessions.size();
    }

    private static void close(
            WebSocketSession session,
            CloseStatus status
    ) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // Session teardown is best effort.
        }
    }

    public enum DeliveryAttempt {
        DELIVERED,
        REJECTED_BEFORE_SEND,
        UNKNOWN
    }

    private record SessionHandle(
            long generation,
            WebSocketSession session
    ) {
    }
}
