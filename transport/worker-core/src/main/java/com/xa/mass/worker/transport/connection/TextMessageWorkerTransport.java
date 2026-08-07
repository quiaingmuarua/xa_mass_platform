package com.xa.mass.worker.transport.connection;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owns the Worker Delivery protocol over one reconnecting text-message client:
 * connection Bind, serial command execution, and one pending result.
 */
public final class TextMessageWorkerTransport
        implements AutoCloseable, TextMessageClient.Listener {

    public interface Observer {

        void onReady();

        void onDisconnected();

        void onFailure(Throwable error);
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
    };

    private final TextMessageClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final Observer observer;
    private final ExecutorService execution;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private boolean running;
    private boolean closed;
    private boolean bindSent;
    private boolean processing;
    private WorkerResult pendingResult;

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        this(
                client,
                workerId,
                new WorkerCommandDispatcher(definitions),
                NOOP_OBSERVER
        );
    }

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            Collection<? extends WorkerEventDefinition<?>> definitions,
            Observer observer
    ) {
        this(
                client,
                workerId,
                new WorkerCommandDispatcher(definitions),
                observer
        );
    }

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor
    ) {
        this(client, workerId, commandExecutor, NOOP_OBSERVER);
    }

    public TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor,
            Observer observer
    ) {
        if (client == null) {
            throw new IllegalArgumentException(
                    "client must be present"
            );
        }
        if (commandExecutor == null) {
            throw new IllegalArgumentException(
                    "commandExecutor must be present"
            );
        }
        if (observer == null) {
            throw new IllegalArgumentException(
                    "observer must be present"
            );
        }
        this.client = client;
        this.bind = new WorkerConnectionBind(workerId);
        this.commandExecutor = commandExecutor;
        this.observer = observer;
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
            if (!running || closed) {
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
                    || !bindSent
                    || processing
                    || pendingResult != null) {
                closeProtocolError();
                return;
            }
            processing = true;
        }

        try {
            execution.execute(() -> executeCommand(message));
        } catch (RejectedExecutionException error) {
            synchronized (this) {
                processing = false;
                if (running && !closed) {
                    closeProtocolError();
                }
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
        stopped.countDown();
    }

    public synchronized boolean hasPendingResult() {
        return pendingResult != null;
    }

    public synchronized boolean isConnected() {
        return running && bindSent && client.isConnected();
    }

    private void executeCommand(String encodedCommand) {
        Optional<WorkerResult> result;
        try {
            result = commandExecutor.execute(encodedCommand);
        } catch (RuntimeException error) {
            synchronized (this) {
                processing = false;
                if (running && !closed) {
                    closeProtocolError();
                }
            }
            return;
        }

        synchronized (this) {
            processing = false;
            if (!running || closed) {
                return;
            }
            if (result.isPresent()) {
                pendingResult = result.get();
                if (bindSent && client.isConnected()) {
                    sendPending();
                }
            }
        }
    }

    private void sendBind() {
        boolean accepted = client.send(
                codec.encodeWorkerConnectionBind(bind)
        );
        if (!accepted) {
            bindSent = false;
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return;
        }
        bindSent = true;
        if (pendingResult != null) {
            sendPending();
        }
    }

    private void sendPending() {
        WorkerResult sending = pendingResult;
        if (sending == null || !bindSent) {
            return;
        }
        if (!client.send(codec.encodeWorkerResult(sending))) {
            bindSent = false;
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            return;
        }
        if (pendingResult == sending) {
            pendingResult = null;
        }
    }

    private void closeProtocolError() {
        bindSent = false;
        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
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
}
