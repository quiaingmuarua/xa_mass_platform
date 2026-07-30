package com.xa.mass.transport.android.websocket;

import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;

/**
 * Android composition entry for one WebSocket Worker.
 */
public final class AndroidWebSocketWorker implements AutoCloseable {

    private final WebSocketWorkerTransport transport;

    public AndroidWebSocketWorker(
            URI socketUri,
            String workerId,
            Duration requestTimeout,
            Duration reconnectInterval,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        transport = new WebSocketWorkerTransport(
                new AndroidOkHttpTextWebSocketClient(
                        socketUri,
                        requestTimeout,
                        reconnectInterval
                ),
                workerId,
                new WorkerCommandDispatcher(definitions)
        );
    }

    public void start() {
        transport.start();
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    public boolean hasPendingResult() {
        return transport.hasPendingResult();
    }

    @Override
    public void close() {
        transport.close();
    }
}
