package com.xa.mass.worker.transport.websocket;

import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.websocket.client.OkHttpTextWebSocketClient;
import com.xa.mass.worker.transport.websocket.client.TextWebSocketClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class WebSocketWorkerTransport
        implements AutoCloseable, TextWebSocketClient.Listener {

    private static final int BAD_DATA = 1007;
    private static final int UNSUPPORTED_DATA = 1003;
    private static final int SEND_FAILED = 1011;

    private final TextWebSocketClient client;
    private final String workerId;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final ExecutorService execution;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private boolean running;
    private boolean closed;
    private boolean bound;
    private boolean processing;
    private WorkerResult pendingResult;

    public WebSocketWorkerTransport(
            URI serverUrl,
            String workerId,
            Duration requestTimeout,
            Duration reconnectInterval,
            List<? extends WorkerEventDefinition<?>> definitions
    ) {
        this(
                new WorkerCommandDispatcher(definitions),
                serverUrl,
                workerId,
                requestTimeout,
                reconnectInterval
        );
    }

    private WebSocketWorkerTransport(
            WorkerCommandExecutor commandExecutor,
            URI serverUrl,
            String workerId,
            Duration requestTimeout,
            Duration reconnectInterval
    ) {
        this(
                new OkHttpTextWebSocketClient(
                        serverUrl,
                        requestTimeout,
                        reconnectInterval
                ),
                workerId,
                commandExecutor
        );
    }

    public WebSocketWorkerTransport(
            TextWebSocketClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor
    ) {
        if (client == null) {
            throw new IllegalArgumentException(
                    "client must be present"
            );
        }
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        if (commandExecutor == null) {
            throw new IllegalArgumentException(
                    "commandExecutor must be present"
            );
        }
        this.client = client;
        this.workerId = workerId;
        this.commandExecutor = commandExecutor;
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
                        "WebSocketWorkerTransport is closed"
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
        synchronized (this) {
            if (!running || closed) {
                client.closeCurrent(1000, "Worker stopped");
                return;
            }
            bound = false;
            sendBind();
        }
    }

    @Override
    public void onText(String message) {
        synchronized (this) {
            if (!running
                    || closed
                    || !bound
                    || processing
                    || pendingResult != null) {
                closeProtocolError(BAD_DATA);
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
                    closeProtocolError(BAD_DATA);
                }
            }
        }
    }

    @Override
    public synchronized void onBinary() {
        closeProtocolError(UNSUPPORTED_DATA);
    }

    @Override
    public synchronized void onDisconnected() {
        bound = false;
    }

    @Override
    public synchronized void onFailure(Throwable error) {
        bound = false;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            bound = false;
        }
        client.close();
        execution.shutdownNow();
        stopped.countDown();
    }

    public synchronized boolean hasPendingResult() {
        return pendingResult != null;
    }

    public synchronized boolean isConnected() {
        return running && bound && client.isConnected();
    }

    private void executeCommand(String encodedCommand) {
        Optional<WorkerResult> result;
        try {
            result = commandExecutor.execute(encodedCommand);
        } catch (RuntimeException error) {
            synchronized (this) {
                processing = false;
                if (running && !closed) {
                    closeProtocolError(BAD_DATA);
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
                if (bound && client.isConnected()) {
                    sendPending();
                }
            }
        }
    }

    private void sendBind() {
        boolean accepted = client.send(
                codec.encodeWorkerConnectionBind(
                        new WorkerConnectionBind(workerId)
                )
        );
        if (!accepted) {
            bound = false;
            client.closeCurrent(
                    SEND_FAILED,
                    "Worker Delivery send failed"
            );
            return;
        }
        bound = true;
        if (pendingResult != null) {
            sendPending();
        }
    }

    private void sendPending() {
        WorkerResult sending = pendingResult;
        if (sending == null || !bound) {
            return;
        }
        if (!client.send(codec.encodeWorkerResult(sending))) {
            bound = false;
            client.closeCurrent(
                    SEND_FAILED,
                    "Worker Delivery send failed"
            );
            return;
        }
        if (pendingResult == sending) {
            pendingResult = null;
        }
    }

    private void closeProtocolError(int code) {
        bound = false;
        client.closeCurrent(
                code,
                "Invalid Worker Delivery message"
        );
    }
}
