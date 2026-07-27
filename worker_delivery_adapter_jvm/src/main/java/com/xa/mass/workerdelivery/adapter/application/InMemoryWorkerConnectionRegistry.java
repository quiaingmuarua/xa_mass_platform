package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkerConnectionRegistry
        implements WorkerConnectionRegistry {

    private final Map<String, ConnectionHandle> connections =
            new ConcurrentHashMap<>();

    @Override
    public void bind(
            String workerId,
            WorkerConnection connection
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(connection, "connection");
        ConnectionHandle replacement = new ConnectionHandle(connection);
        ConnectionHandle previous = connections.put(
                workerId,
                replacement
        );
        if (previous != null) {
            close(previous, WorkerConnectionCloseReason.REPLACED);
        }
    }

    @Override
    public void unbind(
            String workerId,
            WorkerConnection connection
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(connection, "connection");
        connections.computeIfPresent(workerId, (ignored, current) ->
                current.connection() == connection ? null : current
        );
    }

    @Override
    public CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        ConnectionHandle current = connections.get(workerId);
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
                    workerId,
                    current,
                    WorkerConnectionCloseReason.TRANSPORT_ERROR
            );
        }
        return attempt;
    }

    @Override
    public void closeAll(WorkerConnectionCloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        connections.forEach((workerId, current) ->
                removeAndClose(workerId, current, reason)
        );
    }

    int activeConnectionCount() {
        return connections.size();
    }

    private void removeAndClose(
            String workerId,
            ConnectionHandle handle,
            WorkerConnectionCloseReason reason
    ) {
        if (removeExact(workerId, handle)) {
            close(handle, reason);
        }
    }

    private boolean removeExact(
            String workerId,
            ConnectionHandle expected
    ) {
        return connections.remove(workerId, expected);
    }

    private static void close(
            ConnectionHandle handle,
            WorkerConnectionCloseReason reason
    ) {
        try {
            handle.connection().close(reason);
        } catch (RuntimeException ignored) {
            // Connection teardown is best effort.
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
    }

    private static final class ConnectionHandle {

        private final WorkerConnection connection;

        private ConnectionHandle(WorkerConnection connection) {
            this.connection = connection;
        }

        private WorkerConnection connection() {
            return connection;
        }
    }
}
