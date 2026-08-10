package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Objects;
import java.util.Optional;

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

    private enum State {
        NEW,
        RUNNING,
        TERMINATING,
        CLOSED
    }

    private final TextMessageClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandDispatcher;
    private final Listener listener;

    private State state = State.NEW;
    private boolean bound;
    private boolean terminationNotified;
    private boolean clientClosed;
    private int activeCommands;
    private Throwable terminationFailure;

    TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandDispatcher,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandDispatcher = Objects.requireNonNull(
                commandDispatcher,
                "commandDispatcher"
        );
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        synchronized (this) {
            if (state == State.CLOSED || state == State.TERMINATING) {
                return;
            }
            if (state == State.RUNNING) {
                return;
            }
            state = State.RUNNING;
        }
        try {
            client.start(this);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                if (state == State.RUNNING) {
                    state = State.NEW;
                }
            }
            throw failure;
        }
    }

    @Override
    public void onOpen() {
        synchronized (this) {
            bound = false;
            if (state != State.RUNNING) {
                client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
                return;
            }
        }

        Throwable failure = sendBind();
        if (failure != null) {
            closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            rethrowError(failure);
            return;
        }

        synchronized (this) {
            if (state == State.RUNNING) {
                bound = true;
                return;
            }
        }
        client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
    }

    @Override
    public void onMessage(String message) {
        WorkerCommand command;
        try {
            command = codec.decodeWorkerCommand(message);
        } catch (RuntimeException failure) {
            closeProtocolError();
            return;
        }
        if (command == null || !beginCommand()) {
            closeProtocolError();
            return;
        }

        Throwable failure = null;
        try {
            Optional<WorkerResult> result = Objects.requireNonNull(
                    commandDispatcher.execute(command),
                    "commandDispatcher returned null"
            );
            if (result.isPresent()) {
                sendResult(result.get());
            }
        } catch (Throwable error) {
            failure = error;
            sendResult(workerFailure(command));
        } finally {
            finishCommand();
        }
        rethrowError(failure);
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
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            bound = false;
        }
        closeClientQuietly();
    }

    private Throwable sendBind() {
        try {
            return client.send(codec.encodeWorkerConnectionBind(bind))
                    ? null
                    : new IllegalStateException(
                            "Worker connection bind was not accepted"
                    );
        } catch (Throwable failure) {
            return failure;
        }
    }

    private boolean beginCommand() {
        synchronized (this) {
            if (state != State.RUNNING || !bound) {
                return false;
            }
            activeCommands++;
            return true;
        }
    }

    private void finishCommand() {
        Throwable failure = null;
        boolean notify = false;
        synchronized (this) {
            activeCommands--;
            if (terminationReadyLocked()) {
                terminationNotified = true;
                failure = terminationFailure;
                notify = true;
            }
        }
        if (notify) {
            notifyTerminated(failure);
        }
    }

    private void sendResult(WorkerResult result) {
        synchronized (this) {
            if (state != State.RUNNING || !bound) {
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
            closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        }
        rethrowError(failure);
    }

    private void requestTermination(
            Throwable failure,
            boolean closeClient
    ) {
        Throwable reportedFailure = null;
        boolean notify = false;
        synchronized (this) {
            if (state == State.CLOSED || terminationNotified) {
                return;
            }
            if (state != State.TERMINATING) {
                state = State.TERMINATING;
                terminationFailure = failure;
            }
            bound = false;
        }
        if (closeClient) {
            closeClientQuietly();
        }
        synchronized (this) {
            if (terminationReadyLocked()) {
                terminationNotified = true;
                reportedFailure = terminationFailure;
                notify = true;
            }
        }
        if (notify) {
            notifyTerminated(reportedFailure);
        }
    }

    private boolean terminationReadyLocked() {
        return state == State.TERMINATING
                && !terminationNotified
                && activeCommands == 0;
    }

    private void closeProtocolError() {
        closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
    }

    private void closeCurrent(TextMessageClient.CloseReason reason) {
        synchronized (this) {
            if (state != State.RUNNING) {
                return;
            }
            bound = false;
        }
        client.closeCurrent(reason);
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
