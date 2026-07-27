package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerSessionDirectory.WorkerSessionToken;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryWorkerSessionDirectory
        implements WorkerSessionDirectory {

    private final Map<String, SessionHandle> sessions =
            new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    @Override
    public WorkerSessionToken bind(
            String workerId,
            WorkerConnection connection
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(connection, "connection");
        WorkerSessionToken token = new InMemoryWorkerSessionToken(
                workerId,
                generations.incrementAndGet()
        );
        SessionHandle replacement = new SessionHandle(token, connection);
        SessionHandle previous = sessions.put(workerId, replacement);
        if (previous != null) {
            close(previous, WorkerConnectionCloseReason.REPLACED);
        }
        return token;
    }

    @Override
    public void unbind(WorkerSessionToken token) {
        Objects.requireNonNull(token, "token");
        sessions.computeIfPresent(token.workerId(), (ignored, current) ->
                current.token().equals(token) ? null : current
        );
    }

    @Override
    public boolean isCurrent(WorkerSessionToken token) {
        Objects.requireNonNull(token, "token");
        SessionHandle current = sessions.get(token.workerId());
        return current != null && current.token().equals(token);
    }

    @Override
    public CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        SessionHandle current = sessions.get(workerId);
        if (current == null) {
            return CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
        }
        CommandDeliveryAttempt attempt;
        try {
            attempt = Objects.requireNonNull(
                    current.connection().deliver(command),
                    "WorkerConnection deliver result"
            );
        } catch (RuntimeException error) {
            attempt = CommandDeliveryAttempt.UNKNOWN;
        }
        if (attempt != CommandDeliveryAttempt.DELIVERED) {
            removeAndClose(
                    current,
                    WorkerConnectionCloseReason.TRANSPORT_ERROR
            );
        }
        return attempt;
    }

    @Override
    public void close(
            WorkerSessionToken token,
            WorkerConnectionCloseReason reason
    ) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(reason, "reason");
        SessionHandle current = sessions.get(token.workerId());
        if (current != null && current.token().equals(token)) {
            removeAndClose(current, reason);
        }
    }

    @Override
    public void closeAll(WorkerConnectionCloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        sessions.forEach((ignored, current) ->
                removeAndClose(current, reason)
        );
    }

    int activeSessionCount() {
        return sessions.size();
    }

    private void removeAndClose(
            SessionHandle handle,
            WorkerConnectionCloseReason reason
    ) {
        if (sessions.remove(handle.token().workerId(), handle)) {
            close(handle, reason);
        }
    }

    private static void close(
            SessionHandle handle,
            WorkerConnectionCloseReason reason
    ) {
        try {
            handle.connection().close(reason);
        } catch (RuntimeException ignored) {
            // Session teardown is best effort.
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
    }

    private record SessionHandle(
            WorkerSessionToken token,
            WorkerConnection connection
    ) {
    }

    private record InMemoryWorkerSessionToken(
            String workerId,
            long generation
    ) implements WorkerSessionToken {
        private InMemoryWorkerSessionToken {
            requireWorkerId(workerId);
            if (generation <= 0) {
                throw new IllegalArgumentException(
                        "generation must be positive"
                );
            }
        }
    }
}
