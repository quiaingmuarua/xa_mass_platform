package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs Worker Delivery text protocol over one prepared endpoint.
 */
final class TextMessageWorkerTransport
        implements AutoCloseable, TextMessageClient.Listener {

    @FunctionalInterface
    interface Listener {

        void onTerminated(
                TextMessageWorkerTransport transport,
                Throwable failure
        );
    }

    private final TextMessageClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandDispatcher;
    private final Executor commandExecutor;
    private final Listener listener;
    private final Set<String> inFlightMessageIds = new HashSet<>();

    private boolean startRequested;
    private boolean closed;
    private boolean bound;
    private boolean terminationRequested;
    private boolean terminationNotified;
    private boolean clientClosed;
    private Throwable terminationFailure;
    private long connectionGeneration;

    TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandDispatcher,
            Executor commandExecutor,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandDispatcher = Objects.requireNonNull(
                commandDispatcher,
                "commandDispatcher"
        );
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        synchronized (this) {
            if (closed || terminationRequested || startRequested) {
                return;
            }
            startRequested = true;
        }
        try {
            client.start(this);
        } catch (RuntimeException error) {
            synchronized (this) {
                if (closed || terminationRequested) {
                    return;
                }
                startRequested = false;
            }
            throw error;
        }
    }

    @Override
    public void onOpen() {
        long openedGeneration;
        synchronized (this) {
            bound = false;
            if (!startRequested || closed || terminationRequested) {
                client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
                return;
            }
            openedGeneration = ++connectionGeneration;
        }

        boolean accepted;
        Throwable failure = null;
        try {
            accepted = client.send(
                    codec.encodeWorkerConnectionBind(bind)
            );
        } catch (Throwable error) {
            accepted = false;
            failure = error;
        }
        if (!accepted) {
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            rethrowError(failure);
            return;
        }

        synchronized (this) {
            if (closed
                    || terminationRequested
                    || connectionGeneration != openedGeneration) {
                client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
            } else {
                bound = true;
            }
        }
    }

    @Override
    public void onMessage(String message) {
        WorkerCommand command;
        try {
            command = codec.decodeWorkerCommand(message);
        } catch (RuntimeException error) {
            closeProtocolError();
            return;
        }
        long admittedGeneration = command == null
                ? -1L
                : admit(command.messageId());
        if (command == null || admittedGeneration < 0L) {
            closeProtocolError();
            return;
        }

        try {
            commandExecutor.execute(
                    () -> executeAndReport(command, admittedGeneration)
            );
        } catch (RejectedExecutionException rejected) {
            completeRejected(command, admittedGeneration);
        } catch (Throwable failure) {
            completeFailure(command, admittedGeneration, failure);
            rethrowError(failure);
        }
    }

    @Override
    public void onEndpointTerminated() {
        requestTermination(null, false);
    }

    void requestStop() {
        requestTermination(null, true);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            bound = false;
            terminationRequested = true;
            inFlightMessageIds.clear();
        }
        closeClientQuietly();
    }

    private long admit(String messageId) {
        synchronized (this) {
            if (!closed
                    && !terminationRequested
                    && bound
                    && inFlightMessageIds.add(messageId)) {
                return connectionGeneration;
            }
            return -1L;
        }
    }

    private void completeRejected(
            WorkerCommand command,
            long admittedGeneration
    ) {
        boolean known = removeInFlight(command.messageId());
        if (known) {
            sendResult(workerFailure(command), admittedGeneration);
        }
    }

    private void executeAndReport(
            WorkerCommand command,
            long admittedGeneration
    ) {
        try {
            Optional<WorkerResult> result = Objects.requireNonNull(
                    commandDispatcher.execute(command),
                    "commandDispatcher returned null"
            );
            completeSuccess(command, admittedGeneration, result);
        } catch (Throwable failure) {
            completeFailure(command, admittedGeneration, failure);
            rethrowError(failure);
        }
    }

    private void completeFailure(
            WorkerCommand command,
            long admittedGeneration,
            Throwable failure
    ) {
        if (isInFlight(command.messageId())) {
            sendResult(workerFailure(command), admittedGeneration);
            removeInFlight(command.messageId());
        }
    }

    private void completeSuccess(
            WorkerCommand command,
            long admittedGeneration,
            Optional<WorkerResult> result
    ) {
        Objects.requireNonNull(result, "result");
        if (!isInFlight(command.messageId())) {
            return;
        }
        if (result.isPresent()) {
            sendResult(result.get(), admittedGeneration);
        }
        removeInFlight(command.messageId());
    }

    private boolean isInFlight(String messageId) {
        synchronized (this) {
            return inFlightMessageIds.contains(messageId);
        }
    }

    private boolean removeInFlight(String messageId) {
        boolean known;
        boolean notify;
        Throwable failure;
        synchronized (this) {
            known = inFlightMessageIds.remove(messageId);
            notify = known && terminationReadyLocked();
            if (notify) {
                terminationNotified = true;
            }
            failure = notify ? terminationFailure : null;
        }
        if (notify) {
            notifyTerminated(failure);
        }
        return known;
    }

    private void sendResult(
            WorkerResult result,
            long admittedGeneration
    ) {
        synchronized (this) {
            if (closed
                    || terminationRequested
                    || !bound
                    || connectionGeneration != admittedGeneration) {
                return;
            }
        }

        boolean sent;
        Throwable failure = null;
        try {
            sent = client.send(codec.encodeWorkerResult(result));
        } catch (Throwable error) {
            sent = false;
            failure = error;
        }
        if (!sent) {
            synchronized (this) {
                bound = false;
            }
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        }
        rethrowError(failure);
    }

    private void requestTermination(
            Throwable failure,
            boolean closeClient
    ) {
        boolean notify;
        Throwable reportedFailure;
        synchronized (this) {
            if (closed || terminationNotified) {
                return;
            }
            if (!terminationRequested) {
                terminationRequested = true;
                terminationFailure = failure;
            }
            bound = false;
            notify = terminationReadyLocked();
            if (notify) {
                terminationNotified = true;
            }
            reportedFailure = notify ? terminationFailure : null;
        }
        if (closeClient) {
            closeClientQuietly();
        }
        if (notify) {
            notifyTerminated(reportedFailure);
        }
    }

    private boolean terminationReadyLocked() {
        return !closed
                && terminationRequested
                && !terminationNotified
                && inFlightMessageIds.isEmpty();
    }

    private void closeProtocolError() {
        boolean closeCurrent;
        synchronized (this) {
            closeCurrent = !closed && !terminationRequested;
            if (closeCurrent) {
                bound = false;
            }
        }
        if (closeCurrent) {
            client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
        }
    }

    private void notifyTerminated(Throwable failure) {
        try {
            listener.onTerminated(this, failure);
        } catch (RuntimeException ignored) {
            // The controller owns current-transport callback suppression.
        }
    }

    private void closeClientQuietly() {
        synchronized (this) {
            if (clientClosed) {
                return;
            }
            clientClosed = true;
        }
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Terminal notification must not depend on network teardown.
        }
    }

    private static WorkerResult workerFailure(WorkerCommand command) {
        return new WorkerResult(
                command.messageId(),
                command.src(),
                command.messageType(),
                "1500",
                "null",
                command.forward()
        );
    }

    private static void rethrowError(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }
}
