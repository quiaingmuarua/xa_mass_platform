package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Objects;

/**
 * Runs Worker Delivery over one prepared, reconnecting text endpoint.
 */
final class TextMessageWorkerRuntime
        implements AutoCloseable, TextMessageClient.Listener {

    @FunctionalInterface
    interface CommandReceiver {

        boolean receive(WorkerCommand command);
    }

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
    private final CommandReceiver commandReceiver;
    private final WorkerResultSlot pendingResult;
    private final Listener listener;

    private boolean started;
    private boolean closed;
    private boolean bindSent;
    private boolean exitNotified;

    TextMessageWorkerRuntime(
            TextMessageClient client,
            String workerId,
            CommandReceiver commandReceiver,
            WorkerResultSlot pendingResult,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandReceiver = Objects.requireNonNull(
                commandReceiver,
                "commandReceiver"
        );
        this.pendingResult = Objects.requireNonNull(
                pendingResult,
                "pendingResult"
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
        synchronized (this) {
            if (!started || closed || exitNotified || !bindSent) {
                closeProtocolErrorLocked();
                return;
            }
        }
        WorkerCommand command = codec.decodeWorkerCommand(message);
        if (command == null || !commandReceiver.receive(command)) {
            synchronized (this) {
                if (!closed && !exitNotified) {
                    closeProtocolErrorLocked();
                }
            }
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
        boolean notify;
        synchronized (this) {
            bindSent = false;
            notify = markExitLocked();
        }
        if (notify) {
            notifyExit();
        }
    }

    synchronized boolean isConnected() {
        return started
                && !closed
                && !exitNotified
                && bindSent
                && client.isConnected();
    }

    void flushPendingResult() {
        synchronized (this) {
            if (!isConnected()) {
                return;
            }
            sendPendingLocked();
        }
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
