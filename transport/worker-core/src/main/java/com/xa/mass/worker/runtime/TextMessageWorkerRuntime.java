package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs Worker Delivery over one prepared, reconnecting text endpoint.
 */
final class TextMessageWorkerRuntime
        implements AutoCloseable, TextMessageClient.Listener {

    interface Listener {

        void onStateChanged(
                TextMessageWorkerRuntime runtime,
                Throwable failure
        );

        void onExit(TextMessageWorkerRuntime runtime);
    }

    private final TextMessageClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final ExecutorService commandThread;
    private final Listener listener;

    private boolean started;
    private boolean closed;
    private boolean bindSent;
    private boolean exitNotified;
    private boolean exitRequested;
    private boolean commandInFlight;

    TextMessageWorkerRuntime(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.listener = Objects.requireNonNull(listener, "listener");
        commandThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "xa-worker-command"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException(
                        "TextMessageWorkerRuntime is closed"
                );
            }
            if (started) {
                return;
            }
            started = true;
        }
        try {
            client.start(this);
        } catch (RuntimeException error) {
            synchronized (this) {
                started = false;
            }
            throw error;
        }
    }

    @Override
    public void onOpen() {
        boolean changed = false;
        synchronized (this) {
            if (!started || closed || exitRequested || exitNotified) {
                client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
                return;
            }
            bindSent = false;
            if (sendBindLocked()) {
                changed = true;
            }
        }
        if (changed) {
            notifyStateChanged(null);
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
        if (command == null || !tryAcceptInboundCommand(command)) {
            closeProtocolError();
        }
    }

    @Override
    public void onEndpointTerminated() {
        boolean notify = false;
        synchronized (this) {
            bindSent = false;
            if (closed || exitNotified) {
                return;
            }
            exitRequested = true;
            if (!commandInFlight) {
                notify = markExitLocked();
            }
        }
        notifyStateChanged(null);
        if (notify) {
            notifyExit();
        }
    }

    void requestStop() {
        boolean notify;
        synchronized (this) {
            if (closed || exitRequested || exitNotified) {
                return;
            }
            exitRequested = true;
            bindSent = false;
            notify = !commandInFlight && markExitLocked();
        }
        client.close();
        notifyStateChanged(null);
        if (notify) {
            notifyExit();
        }
    }

    private boolean tryAcceptInboundCommand(WorkerCommand command) {
        Objects.requireNonNull(command, "command");
        synchronized (this) {
            if (!isConnected()
                    || commandInFlight
                    || exitRequested) {
                return false;
            }
            commandInFlight = true;
        }
        try {
            commandThread.execute(() -> executeCommand(command));
            return true;
        } catch (RejectedExecutionException error) {
            synchronized (this) {
                commandInFlight = false;
            }
            return false;
        }
    }

    synchronized boolean isConnected() {
        return started
                && !closed
                && !exitRequested
                && !exitNotified
                && bindSent
                && client.isConnected();
    }

    synchronized boolean isExiting() {
        return exitRequested || exitNotified;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            started = false;
            bindSent = false;
            exitRequested = true;
        }
        client.close();
        commandThread.shutdownNow();
    }

    private void executeCommand(WorkerCommand command) {
        Optional<WorkerResult> result;
        RuntimeException failure = null;
        try {
            result = commandExecutor.execute(command);
        } catch (RuntimeException error) {
            result = Optional.empty();
            failure = error;
        }
        finishCommand(result, failure);
    }

    private void finishCommand(
            Optional<WorkerResult> result,
            RuntimeException failure
    ) {
        boolean notifyExit = false;
        RuntimeException reportedFailure = failure;
        synchronized (this) {
            commandInFlight = false;
            if (closed) {
                return;
            }
            if (exitRequested) {
                notifyExit = markExitLocked();
            } else if (reportedFailure == null
                    && result.isPresent()
                    && isConnected()) {
                sendResultLocked(result.get());
            }
        }
        if (reportedFailure != null) {
            notifyStateChanged(reportedFailure);
        }
        if (notifyExit) {
            notifyExit();
        }
    }

    private boolean sendBindLocked() {
        if (!client.send(codec.encodeWorkerConnectionBind(bind))) {
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return false;
        }
        bindSent = true;
        return bindSent && client.isConnected();
    }

    private void sendResultLocked(WorkerResult result) {
        if (!client.send(codec.encodeWorkerResult(result))) {
            bindSent = false;
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        }
    }

    private void closeProtocolErrorLocked() {
        bindSent = false;
        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
    }

    private synchronized void closeProtocolError() {
        if (!closed && !exitRequested && !exitNotified) {
            closeProtocolErrorLocked();
        }
    }

    private boolean markExitLocked() {
        if (closed || exitNotified) {
            return false;
        }
        exitNotified = true;
        return true;
    }

    private void notifyStateChanged(Throwable failure) {
        try {
            listener.onStateChanged(this, failure);
        } catch (RuntimeException ignored) {
            // Observation cannot interrupt the network state machine.
        }
    }

    private void notifyExit() {
        try {
            listener.onExit(this);
        } catch (RuntimeException ignored) {
            // WorkerLoop owns current-runtime callback suppression.
        }
    }
}
