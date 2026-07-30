package com.xa.mass.worker.transport.socket;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.socket.client.JdkLineSocketClient;
import com.xa.mass.transport.client.LineSocketClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public final class SocketWorkerTransport
        implements AutoCloseable, LineSocketClient.Listener {

    private final LineSocketClient client;
    private final String workerId;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private boolean running;
    private boolean closed;
    private boolean bound;
    private boolean processing;
    private WorkerResult pendingResult;

    public SocketWorkerTransport(
            URI socketUri,
            String workerId,
            Duration connectTimeout,
            Duration reconnectInterval,
            List<? extends WorkerEventDefinition<?>> definitions
    ) {
        this(
                new WorkerCommandDispatcher(definitions),
                socketUri,
                workerId,
                connectTimeout,
                reconnectInterval
        );
    }

    private SocketWorkerTransport(
            WorkerCommandExecutor commandExecutor,
            URI socketUri,
            String workerId,
            Duration connectTimeout,
            Duration reconnectInterval
    ) {
        this(
                new JdkLineSocketClient(
                        socketUri,
                        connectTimeout,
                        reconnectInterval
                ),
                workerId,
                commandExecutor
        );
    }

    public SocketWorkerTransport(
            LineSocketClient client,
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
    }

    public void start() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException(
                        "SocketWorkerTransport is closed"
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
    public synchronized void onOpen() {
        if (!running || closed) {
            return;
        }
        bound = false;
        if (!client.sendLine(
                codec.encodeWorkerConnectionBind(
                        new WorkerConnectionBind(workerId)
                )
        )) {
            return;
        }
        bound = true;
        sendPending();
    }

    @Override
    public void onLine(String message) {
        synchronized (this) {
            if (!running
                    || closed
                    || !bound
                    || processing
                    || pendingResult != null) {
                throw protocolFailure();
            }
            processing = true;
        }

        Optional<WorkerResult> result;
        try {
            result = commandExecutor.execute(message);
        } catch (RuntimeException error) {
            synchronized (this) {
                processing = false;
                bound = false;
            }
            throw error;
        }

        synchronized (this) {
            processing = false;
            if (!running || closed) {
                return;
            }
            if (result.isPresent()) {
                pendingResult = result.get();
                sendPending();
            }
        }
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
        stopped.countDown();
    }

    public synchronized boolean isConnected() {
        return running && bound && client.isConnected();
    }

    public synchronized boolean hasPendingResult() {
        return pendingResult != null;
    }

    private void sendPending() {
        WorkerResult sending = pendingResult;
        if (sending == null || !bound) {
            return;
        }
        if (!client.sendLine(codec.encodeWorkerResult(sending))) {
            bound = false;
            return;
        }
        if (pendingResult == sending) {
            pendingResult = null;
        }
    }

    private static WorkerException protocolFailure() {
        return new WorkerException(
                WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                "socket.receiveCommand",
                "Invalid Worker Delivery command sequence",
                null
        );
    }
}
