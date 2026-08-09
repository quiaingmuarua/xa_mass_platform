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
 * Runs Worker Delivery protocol over one prepared, reconnecting text endpoint.
 */
final class TextMessageWorkerRuntime
        implements AutoCloseable, TextMessageClient.Listener {

    @FunctionalInterface
    interface Listener {

        void onTerminated(
                TextMessageWorkerRuntime runtime,
                Throwable failure
        );
    }

    private final TextMessageClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final ExecutorService handlerExecutor;
    private final Listener listener;

    private boolean startRequested;
    private boolean closed;
    private boolean bound;
    private boolean terminationRequested;
    private boolean terminationNotified;
    private Throwable terminationFailure;
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
        boolean reject;
        synchronized (this) {
            reject = !startRequested || closed || terminationRequested;
            bound = false;
        }
        if (reject) {
            client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
            return;
        }

        boolean accepted;
        try {
            accepted = client.send(
                    codec.encodeWorkerConnectionBind(bind)
            );
        } catch (RuntimeException error) {
            terminateWithFailure(error);
            return;
        }
        if (!accepted) {
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return;
        }

        boolean closeCurrent;
        synchronized (this) {
            closeCurrent = closed || terminationRequested;
            if (!closeCurrent) {
                bound = true;
            }
        }
        if (closeCurrent) {
            client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
        }
    }

    @Override
    public void onMessage(String message) {
        synchronized (this) {
            if (closed || terminationRequested) {
                return;
            }
        }

        WorkerCommand command;
        try {
            command = codec.decodeWorkerCommand(message);
        } catch (RuntimeException error) {
            closeProtocolError();
            return;
        }
        if (command == null) {
            closeProtocolError();
            return;
        }

        CommandExecution execution;
        synchronized (this) {
            if (closed || terminationRequested) {
                return;
            }
            if (!bound || activeCommand != null) {
                execution = null;
            } else {
                execution = new CommandExecution(command);
                activeCommand = execution;
            }
        }
        if (execution == null) {
            closeProtocolError();
            return;
        }

        try {
            handlerExecutor.execute(execution.task);
        } catch (RejectedExecutionException error) {
            failCommandExecution(execution, error);
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
        CommandExecution command;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            bound = false;
            terminationRequested = true;
            command = activeCommand;
            activeCommand = null;
        }
        if (command != null) {
            command.task.cancel(true);
        }
        closeClientQuietly();
    }

    private void executeCommand(CommandExecution execution) {
        Optional<WorkerResult> result;
        try {
            result = Objects.requireNonNull(
                    commandExecutor.execute(execution.command),
                    "commandExecutor returned null"
            );
        } catch (RuntimeException error) {
            failCommandExecution(execution, error);
            return;
        }

        WorkerResult sending = null;
        boolean completeWithoutSend = false;
        synchronized (this) {
            if (activeCommand != execution) {
                return;
            }
            if (closed || terminationRequested || !bound
                    || !result.isPresent()) {
                completeWithoutSend = true;
            } else {
                sending = result.get();
            }
        }
        if (completeWithoutSend) {
            completeCommand(execution, false);
            return;
        }

        boolean sent;
        try {
            sent = client.send(codec.encodeWorkerResult(sending));
        } catch (RuntimeException ignored) {
            sent = false;
        }
        completeCommand(execution, !sent);
        if (!sent) {
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        }
    }

    private void completeCommand(
            CommandExecution execution,
            boolean sendFailed
    ) {
        boolean notify = false;
        Throwable failure = null;
        synchronized (this) {
            if (activeCommand != execution) {
                return;
            }
            activeCommand = null;
            if (sendFailed) {
                bound = false;
            }
            if (terminationReadyLocked()) {
                terminationNotified = true;
                notify = true;
                failure = terminationFailure;
            }
        }
        if (notify) {
            notifyTerminated(failure);
        }
    }

    private void failCommandExecution(
            CommandExecution execution,
            RuntimeException error
    ) {
        boolean notify = false;
        Throwable failure = null;
        synchronized (this) {
            if (activeCommand != execution) {
                return;
            }
            activeCommand = null;
            requestTerminationLocked(error);
            if (terminationReadyLocked()) {
                terminationNotified = true;
                notify = true;
                failure = terminationFailure;
            }
        }
        closeClientQuietly();
        if (notify) {
            notifyTerminated(failure);
        }
    }

    private void terminateWithFailure(RuntimeException error) {
        requestTermination(error, true);
    }

    private void requestTermination(
            Throwable failure,
            boolean closeClient
    ) {
        boolean notify = false;
        Throwable reportedFailure = null;
        synchronized (this) {
            if (closed || terminationNotified) {
                return;
            }
            requestTerminationLocked(failure);
            if (terminationReadyLocked()) {
                terminationNotified = true;
                notify = true;
                reportedFailure = terminationFailure;
            }
        }
        if (closeClient) {
            closeClientQuietly();
        }
        if (notify) {
            notifyTerminated(reportedFailure);
        }
    }

    private void requestTerminationLocked(Throwable failure) {
        if (!terminationRequested) {
            terminationRequested = true;
            terminationFailure = failure;
        }
        bound = false;
    }

    private boolean terminationReadyLocked() {
        return !closed
                && terminationRequested
                && !terminationNotified
                && activeCommand == null;
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
            // WorkerLoop owns current-runtime callback suppression.
        }
    }

    private void closeClientQuietly() {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Terminal notification and Handler cleanup must still complete.
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
