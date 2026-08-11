package com.xa.mass.worker.runtime;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.ADAPTER;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(
            TextMessageWorkerTransport.class.getName()
    );

    private final TextMessageClient client;
    private final String workerId;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandDispatcher;
    private final Listener listener;

    private State state = State.NEW;
    private boolean terminationNotified;
    private boolean clientClosed;
    private Throwable terminationFailure;

    TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandDispatcher,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.workerId = requireNonBlank(workerId, "workerId");
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
            if (state != State.RUNNING) {
                client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
                return;
            }
        }

        Throwable failure = sendIdentity();
        if (failure != null) {
            closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            rethrowError(failure);
            return;
        }

        synchronized (this) {
            if (state == State.RUNNING) {
                return;
            }
        }
        client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
    }

    @Override
    public void onMessage(String message) {
        try {
            WorkerCommand command = codec.decodeWorkerCommand(message);
            if (command == null) {
                log(
                        WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                        "command.decode",
                        null
                );
                return;
            }
            if (isConnectionClose(command)) {
                if (System.currentTimeMillis()
                        < command.executeBeforeMillis()) {
                    requestTermination(null, true);
                }
                return;
            }

            Optional<WorkerResult> result = Objects.requireNonNull(
                    commandDispatcher.execute(command),
                    "commandDispatcher returned null"
            );
            if (!result.isPresent()) {
                return;
            }

            String encoded = codec.encodeWorkerResult(result.get());
            if (!client.send(encoded)) {
                log(
                        WorkerErrorCode.RESULT_SUBMIT_FAILED,
                        "result.send",
                        null
                );
            }
        } catch (RuntimeException unexpected) {
            log(
                    WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "command.process",
                    unexpected
            );
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
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
        }
        closeClientQuietly();
    }

    private Throwable sendIdentity() {
        try {
            WorkerResult identity = new WorkerResult(
                    UUID.randomUUID().toString(),
                    ADAPTER,
                    WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                    "200",
                    workerId,
                    ""
            );
            return client.send(codec.encodeWorkerResult(identity))
                    ? null
                    : new IllegalStateException(
                            "Worker connection identity was not accepted"
                    );
        } catch (Throwable failure) {
            return failure;
        }
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
                && !terminationNotified;
    }

    private void closeCurrent(TextMessageClient.CloseReason reason) {
        synchronized (this) {
            if (state != State.RUNNING) {
                return;
            }
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

    private void log(
            WorkerErrorCode errorCode,
            String operation,
            RuntimeException failure
    ) {
        LOGGER.log(
                Level.WARNING,
                "errorCode={0} operation={1} workerId={2} failureType={3}",
                new Object[]{
                        errorCode.code(),
                        operation,
                        workerId,
                        failure == null
                                ? "none"
                                : failure.getClass().getSimpleName()
                }
        );
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

    private static boolean isConnectionClose(WorkerCommand command) {
        return command.src() == ADAPTER
                && WORKER_CONNECTION_CLOSE_EVENT_CODE.equals(
                command.messageType()
        );
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private static void rethrowError(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }
}
