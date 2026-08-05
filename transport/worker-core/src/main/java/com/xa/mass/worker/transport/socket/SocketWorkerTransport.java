package com.xa.mass.worker.transport.socket;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.transport.client.LineSocketClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public final class SocketWorkerTransport
        implements AutoCloseable, LineSocketClient.Listener {

    private final LineSocketClient client;
    private final WorkerConnectionBind bind;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private boolean running;
    private boolean closed;
    private boolean bindSent;
    private boolean processing;
    private WorkerResult pendingResult;

    public SocketWorkerTransport(
            LineSocketClient client,
            String workerId,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        this(
                client,
                workerId,
                new WorkerCommandDispatcher(definitions)
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
        if (commandExecutor == null) {
            throw new IllegalArgumentException(
                    "commandExecutor must be present"
            );
        }
        this.client = client;
        this.bind = new WorkerConnectionBind(workerId);
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
        bindSent = false;
        if (!client.sendLine(
                codec.encodeWorkerConnectionBind(bind)
        )) {
            return;
        }
        bindSent = true;
        sendPending();
    }

    @Override
    public void onLine(String message) {
        synchronized (this) {
            if (!running
                    || closed
                    || !bindSent
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
                bindSent = false;
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
        bindSent = false;
    }

    @Override
    public synchronized void onFailure(Throwable error) {
        bindSent = false;
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
        stopped.countDown();
    }

    public synchronized boolean isConnected() {
        return running && bindSent && client.isConnected();
    }

    public synchronized boolean hasPendingResult() {
        return pendingResult != null;
    }

    private void sendPending() {
        WorkerResult sending = pendingResult;
        if (sending == null || !bindSent) {
            return;
        }
        if (!client.sendLine(codec.encodeWorkerResult(sending))) {
            bindSent = false;
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
