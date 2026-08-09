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
import java.util.concurrent.FutureTask;
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
    private final ExecutorService handlerExecutor;
    private final Listener listener;

    private boolean started;
    private boolean closed;
    private boolean bindSent;
    private boolean exitNotified;
    private boolean exitRequested;
    private CommandExecution activeCommand;

    TextMessageWorkerRuntime(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor,
            ExecutorService handlerExecutor,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.handlerExecutor = Objects.requireNonNull(
                handlerExecutor,
                "handlerExecutor"
        );
        this.listener = Objects.requireNonNull(listener, "listener");
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
            if (activeCommand == null) {
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
            notify = activeCommand == null && markExitLocked();
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
                    || activeCommand != null
                    || exitRequested) {
                return false;
            }
            CommandExecution execution = new CommandExecution(command);
            activeCommand = execution;
            try {
                handlerExecutor.execute(execution.task);
                return true;
            } catch (RejectedExecutionException error) {
                activeCommand = null;
                return false;
            }
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
        CommandExecution command;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            started = false;
            bindSent = false;
            exitRequested = true;
            command = activeCommand;
            activeCommand = null;
        }
        client.close();
        if (command != null) {
            command.task.cancel(true);
        }
    }

    private void executeCommand(CommandExecution execution) {
        Optional<WorkerResult> result;
        RuntimeException failure = null;
        try {
            result = commandExecutor.execute(execution.command);
        } catch (RuntimeException error) {
            result = Optional.empty();
            failure = error;
        }
        finishCommand(execution, result, failure);
    }

    private void finishCommand(
            CommandExecution execution,
            Optional<WorkerResult> result,
            RuntimeException failure
    ) {
        boolean notifyExit = false;
        RuntimeException reportedFailure = failure;
        synchronized (this) {
            if (activeCommand != execution) {
                return;
            }
            activeCommand = null;
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

    private final class CommandExecution {

        private final WorkerCommand command;
        private final FutureTask<Void> task;

        private CommandExecution(WorkerCommand command) {
            this.command = command;
            task = new FutureTask<>(() -> {
                executeCommand(this);
                return null;
            });
        }
    }
}
