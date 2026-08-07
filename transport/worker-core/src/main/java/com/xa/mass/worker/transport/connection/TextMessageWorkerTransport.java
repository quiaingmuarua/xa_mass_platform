package com.xa.mass.worker.transport.connection;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owns Worker Delivery protocol handling over one reconnecting text client.
 */
public final class TextMessageWorkerTransport
        implements AutoCloseable, TextMessageClient.Listener {

    public interface Observer {

        void onReady();

        void onDisconnected();

        void onFailure(Throwable error);

        void onReconnectExhausted();
    }

    /**
     * Opaque one-result slot shared by transports within one Worker start.
     */
    public static final class PendingResultSlot implements AutoCloseable {

        private WorkerResult result;
        private boolean closed;

        public PendingResultSlot() {
        }

        private synchronized boolean offer(WorkerResult value) {
            Objects.requireNonNull(value, "result");
            if (closed || result != null) {
                return false;
            }
            result = value;
            return true;
        }

        private synchronized WorkerResult peek() {
            return closed ? null : result;
        }

        private synchronized void clearIfSame(WorkerResult expected) {
            if (!closed && result == expected) {
                result = null;
            }
        }

        public synchronized boolean hasPendingResult() {
            return !closed && result != null;
        }

        @Override
        public synchronized void close() {
            closed = true;
            result = null;
        }
    }

    private static final Observer NOOP_OBSERVER = new Observer() {
        @Override
        public void onReady() {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onFailure(Throwable error) {
        }

        @Override
        public void onReconnectExhausted() {
        }
    };

    private final TextMessageClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final PendingResultSlot pendingResultSlot;
    private final boolean ownsPendingResultSlot;
    private final Observer observer;
    private final ExecutorService execution;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private boolean running;
    private boolean closed;
    private boolean bindSent;
    private boolean processing;
    private boolean reconnectExhausted;
    private boolean exhaustionNotified;

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        this(
                client,
                workerId,
                new WorkerCommandDispatcher(definitions),
                new PendingResultSlot(),
                true,
                NOOP_OBSERVER
        );
    }

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor
    ) {
        this(
                client,
                workerId,
                commandExecutor,
                new PendingResultSlot(),
                true,
                NOOP_OBSERVER
        );
    }

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor,
            Observer observer
    ) {
        this(
                client,
                workerId,
                commandExecutor,
                new PendingResultSlot(),
                true,
                observer
        );
    }

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor,
            PendingResultSlot pendingResultSlot,
            Observer observer
    ) {
        this(
                client,
                workerId,
                commandExecutor,
                pendingResultSlot,
                false,
                observer
        );
    }

    private TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor,
            PendingResultSlot pendingResultSlot,
            boolean ownsPendingResultSlot,
            Observer observer
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.bind = new WorkerConnectionBind(workerId);
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.pendingResultSlot = Objects.requireNonNull(
                pendingResultSlot,
                "pendingResultSlot"
        );
        this.ownsPendingResultSlot = ownsPendingResultSlot;
        this.observer = Objects.requireNonNull(observer, "observer");
        execution = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-command-execution"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException(
                        "TextMessageWorkerTransport is closed"
                );
            }
            if (running) {
                return;
            }
            running = true;
        }
        try {
            client.start(this);
        } catch (RuntimeException error) {
            synchronized (this) {
                running = false;
            }
            throw error;
        }
    }

    public void runForever() throws InterruptedException {
        start();
        stopped.await();
    }

    @Override
    public void onOpen() {
        boolean ready;
        synchronized (this) {
            if (!running || closed || reconnectExhausted) {
                client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
                return;
            }
            bindSent = false;
            sendBind();
            ready = bindSent && client.isConnected();
        }
        if (ready) {
            notifyReady();
        }
    }

    @Override
    public void onMessage(String message) {
        synchronized (this) {
            if (!running
                    || closed
                    || reconnectExhausted
                    || !bindSent
                    || processing
                    || pendingResultSlot.hasPendingResult()) {
                closeProtocolError();
                return;
            }
            processing = true;
        }

        try {
            execution.execute(() -> executeCommand(message));
        } catch (RejectedExecutionException error) {
            boolean notifyExhausted;
            synchronized (this) {
                processing = false;
                if (running && !closed && !reconnectExhausted) {
                    closeProtocolError();
                }
                notifyExhausted = markExhaustionNotificationLocked();
            }
            if (notifyExhausted) {
                notifyReconnectExhausted();
            }
        }
    }

    @Override
    public void onDisconnected() {
        synchronized (this) {
            bindSent = false;
        }
        notifyDisconnected();
    }

    @Override
    public void onFailure(Throwable error) {
        synchronized (this) {
            bindSent = false;
        }
        notifyFailure(error);
    }

    @Override
    public void onReconnectExhausted() {
        boolean notify;
        synchronized (this) {
            bindSent = false;
            reconnectExhausted = true;
            notify = markExhaustionNotificationLocked();
        }
        if (notify) {
            notifyReconnectExhausted();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            bindSent = false;
        }
        client.close();
        execution.shutdownNow();
        if (ownsPendingResultSlot) {
            pendingResultSlot.close();
        }
        stopped.countDown();
    }

    public boolean hasPendingResult() {
        return pendingResultSlot.hasPendingResult();
    }

    public synchronized boolean isConnected() {
        return running && bindSent && client.isConnected();
    }

    private void executeCommand(String encodedCommand) {
        Optional<WorkerResult> result;
        try {
            result = commandExecutor.execute(encodedCommand);
        } catch (RuntimeException error) {
            boolean notifyExhausted;
            synchronized (this) {
                processing = false;
                if (running && !closed && !reconnectExhausted) {
                    closeProtocolError();
                }
                notifyExhausted = markExhaustionNotificationLocked();
            }
            if (notifyExhausted) {
                notifyReconnectExhausted();
            }
            return;
        }

        boolean notifyExhausted;
        synchronized (this) {
            processing = false;
            if (running && !closed && result.isPresent()) {
                if (!pendingResultSlot.offer(result.get())) {
                    closeProtocolError();
                } else if (bindSent && client.isConnected()) {
                    sendPending();
                }
            }
            notifyExhausted = markExhaustionNotificationLocked();
        }
        if (notifyExhausted) {
            notifyReconnectExhausted();
        }
    }

    private void sendBind() {
        if (!client.send(codec.encodeWorkerConnectionBind(bind))) {
            bindSent = false;
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return;
        }
        bindSent = true;
        if (pendingResultSlot.hasPendingResult()) {
            sendPending();
        }
    }

    private void sendPending() {
        WorkerResult sending = pendingResultSlot.peek();
        if (sending == null || !bindSent) {
            return;
        }
        if (!client.send(codec.encodeWorkerResult(sending))) {
            bindSent = false;
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return;
        }
        pendingResultSlot.clearIfSame(sending);
    }

    private void closeProtocolError() {
        bindSent = false;
        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
    }

    private boolean markExhaustionNotificationLocked() {
        if (!running
                || closed
                || !reconnectExhausted
                || processing
                || exhaustionNotified) {
            return false;
        }
        exhaustionNotified = true;
        return true;
    }

    private void notifyReady() {
        try {
            observer.onReady();
        } catch (RuntimeException ignored) {
            // Host observation cannot interrupt Worker protocol handling.
        }
    }

    private void notifyDisconnected() {
        try {
            observer.onDisconnected();
        } catch (RuntimeException ignored) {
            // Host observation cannot interrupt Worker protocol handling.
        }
    }

    private void notifyFailure(Throwable error) {
        try {
            observer.onFailure(error);
        } catch (RuntimeException ignored) {
            // Host observation cannot interrupt Worker protocol handling.
        }
    }

    private void notifyReconnectExhausted() {
        try {
            observer.onReconnectExhausted();
        } catch (RuntimeException ignored) {
            // Host observation cannot interrupt Worker protocol handling.
        }
    }
}
