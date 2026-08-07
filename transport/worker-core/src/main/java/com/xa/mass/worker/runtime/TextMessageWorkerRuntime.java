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
    private final WorkerResultSlot pendingResult;
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
            WorkerResultSlot pendingResult,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.pendingResult = Objects.requireNonNull(
                pendingResult,
                "pendingResult"
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
            if (!started || closed || exitNotified) {
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
        if (command == null || !send(command)) {
            closeProtocolError();
        }
    }

    @Override
    public void onDisconnected() {
        synchronized (this) {
            bindSent = false;
            if (closed || exitNotified) {
                return;
            }
        }
        notifyStateChanged(null);
    }

    @Override
    public void onFailure(Throwable error) {
        synchronized (this) {
            bindSent = false;
            if (closed || exitNotified) {
                return;
            }
        }
        notifyStateChanged(error);
    }

    @Override
    public void onReconnectExhausted() {
        boolean notify = false;
        synchronized (this) {
            bindSent = false;
            if (closed || exitNotified) {
                return;
            }
            if (commandInFlight) {
                exitRequested = true;
            } else {
                notify = markExitLocked();
            }
        }
        if (notify) {
            notifyExit();
        }
    }

    boolean send(WorkerCommand command) {
        Objects.requireNonNull(command, "command");
        synchronized (this) {
            if (!isConnected()
                    || commandInFlight
                    || pendingResult.hasResult()) {
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
                && !exitNotified
                && bindSent
                && client.isConnected();
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
            if (reportedFailure == null
                    && result.isPresent()
                    && !pendingResult.offer(result.get())) {
                reportedFailure = new IllegalStateException(
                        "Worker result slot is occupied"
                );
            }
            if (exitRequested) {
                notifyExit = markExitLocked();
            } else if (isConnected()) {
                sendPendingLocked();
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
        sendPendingLocked();
        return bindSent && client.isConnected();
    }

    private void sendPendingLocked() {
        WorkerResult sending = pendingResult.peek();
        if (sending == null || !bindSent) {
            return;
        }
        if (!client.send(codec.encodeWorkerResult(sending))) {
            bindSent = false;
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return;
        }
        pendingResult.clearIfSame(sending);
    }

    private void closeProtocolErrorLocked() {
        bindSent = false;
        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
    }

    private synchronized void closeProtocolError() {
        if (!closed && !exitNotified) {
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
            // The Worker Loop owns stale callback suppression.
        }
    }
}
