package com.xa.mass.worker.transport.websocket;

import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class WebSocketWorkerTransport
        implements AutoCloseable {

    private static final int BAD_DATA = 1007;
    private static final int UNSUPPORTED_DATA = 1003;

    private final WebSocketConnector connector;
    private final Runnable closeConnector;
    private final URI socketUri;
    private final String workerId;
    private final Duration reconnectInterval;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService execution;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final WebSocketListener listener = new TransportListener();

    private boolean running;
    private boolean closed;
    private boolean reconnectScheduled;
    private boolean bound;
    private boolean processing;
    private WebSocket socket;
    private WorkerResult pendingResult;

    public WebSocketWorkerTransport(
            URI serverUrl,
            String workerId,
            Duration requestTimeout,
            Duration reconnectInterval,
            WorkerCommandExecutor commandExecutor
    ) {
        this(
                clientConnector(requestTimeout),
                serverUrl,
                workerId,
                reconnectInterval,
                commandExecutor
        );
    }

    WebSocketWorkerTransport(
            ConnectorResources resources,
            URI serverUrl,
            String workerId,
            Duration reconnectInterval,
            WorkerCommandExecutor commandExecutor
    ) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        this.connector = resources.connector;
        this.closeConnector = resources.close;
        this.socketUri = workerSocketUri(serverUrl);
        this.workerId = workerId;
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        if (commandExecutor == null) {
            throw new IllegalArgumentException(
                    "commandExecutor must be present"
            );
        }
        this.commandExecutor = commandExecutor;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-websocket-reconnect"
            );
            thread.setDaemon(true);
            return thread;
        });
        execution = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-command-execution"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "WebSocketWorkerTransport is closed"
            );
        }
        if (running) {
            return;
        }
        running = true;
        connect();
    }

    public void runForever() throws InterruptedException {
        start();
        stopped.await();
    }

    private synchronized void handleOpen(
            WebSocket webSocket,
            Response response
    ) {
        if (!running || closed) {
            webSocket.cancel();
            return;
        }
        socket = webSocket;
        bound = false;
        reconnectScheduled = false;
        sendBind(webSocket);
    }

    private void handleText(WebSocket webSocket, String text) {
        synchronized (this) {
            if (!isCurrentBound(webSocket)
                    || processing
                    || pendingResult != null) {
                closeProtocolError(webSocket, BAD_DATA);
                return;
            }
            processing = true;
        }

        try {
            execution.execute(() -> executeCommand(text));
        } catch (RejectedExecutionException error) {
            synchronized (this) {
                processing = false;
                if (running && !closed) {
                    closeProtocolError(webSocket, BAD_DATA);
                }
            }
        }
    }

    private synchronized void handleBinary(
            WebSocket webSocket,
            ByteString bytes
    ) {
        closeProtocolError(webSocket, UNSUPPORTED_DATA);
    }

    private synchronized void handleClosing(
            WebSocket webSocket,
            int code,
            String reason
    ) {
        webSocket.close(code, reason);
        disconnect(webSocket);
    }

    private synchronized void handleClosed(
            WebSocket webSocket,
            int code,
            String reason
    ) {
        disconnect(webSocket);
    }

    private synchronized void handleFailure(
            WebSocket webSocket,
            Throwable error,
            Response response
    ) {
        disconnect(webSocket);
    }

    @Override
    public void close() {
        WebSocket current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            reconnectScheduled = false;
            bound = false;
            current = socket;
            socket = null;
        }
        if (current != null) {
            current.close(1000, "Worker stopped");
            current.cancel();
        }
        scheduler.shutdownNow();
        execution.shutdownNow();
        closeConnector.run();
        stopped.countDown();
    }

    public synchronized boolean hasPendingResult() {
        return pendingResult != null;
    }

    public synchronized boolean isConnected() {
        return running && socket != null && bound;
    }

    public URI socketUri() {
        return socketUri;
    }

    private void executeCommand(String encodedFrame) {
        Optional<WorkerResult> result;
        try {
            result = commandExecutor.execute(encodedFrame);
        } catch (RuntimeException error) {
            synchronized (this) {
                processing = false;
                WebSocket current = socket;
                if (current != null) {
                    closeProtocolError(current, BAD_DATA);
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
                WebSocket current = socket;
                if (current != null && bound) {
                    sendPending(current);
                }
            }
        }
    }

    private synchronized void connect() {
        if (!running || closed) {
            return;
        }
        try {
            connector.connect(socketUri, listener);
        } catch (RuntimeException error) {
            scheduleReconnect();
        }
    }

    private void sendBind(WebSocket webSocket) {
        boolean accepted;
        try {
            accepted = webSocket.send(
                    codec.encodeWorkerConnectionBind(
                            new WorkerConnectionBind(workerId)
                    )
            );
        } catch (RuntimeException error) {
            accepted = false;
        }
        if (!accepted) {
            failBeforeSend(webSocket);
            return;
        }
        bound = true;
        if (pendingResult != null) {
            sendPending(webSocket);
        }
    }

    private void sendPending(WebSocket webSocket) {
        WorkerResult sending = pendingResult;
        if (sending == null || !isCurrentBound(webSocket)) {
            return;
        }
        boolean accepted;
        try {
            accepted = webSocket.send(codec.encodeWorkerResult(sending));
        } catch (RuntimeException error) {
            accepted = false;
        }
        if (!accepted) {
            failBeforeSend(webSocket);
            return;
        }
        if (pendingResult == sending) {
            pendingResult = null;
        }
    }

    private void closeProtocolError(WebSocket webSocket, int code) {
        if (webSocket == socket) {
            socket = null;
            bound = false;
        }
        if (!webSocket.close(code, "Invalid Worker Delivery message")) {
            webSocket.cancel();
        }
        scheduleReconnect();
    }

    private void failBeforeSend(WebSocket webSocket) {
        if (webSocket == socket) {
            socket = null;
            bound = false;
        }
        webSocket.cancel();
        scheduleReconnect();
    }

    private void disconnect(WebSocket webSocket) {
        if (webSocket == socket) {
            socket = null;
            bound = false;
            scheduleReconnect();
        }
    }

    private boolean isCurrentBound(WebSocket webSocket) {
        return running
                && !closed
                && webSocket == socket
                && bound;
    }

    private void scheduleReconnect() {
        if (!running || closed || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        try {
            scheduler.schedule(
                    () -> {
                        synchronized (WebSocketWorkerTransport.this) {
                            reconnectScheduled = false;
                            if (running && !closed && socket == null) {
                                connect();
                            }
                        }
                    },
                    reconnectInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            reconnectScheduled = false;
        }
    }

    private static ConnectorResources clientConnector(Duration timeout) {
        long millis = requirePositive(timeout, "requestTimeout").toMillis();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        return new ConnectorResources(
                (uri, listener) -> client.newWebSocket(
                        new Request.Builder()
                                .url(uri.toString())
                                .build(),
                        listener
                ),
                () -> {
                    client.dispatcher().cancelAll();
                    client.connectionPool().evictAll();
                    client.dispatcher()
                            .executorService()
                            .shutdownNow();
                }
        );
    }

    private static URI workerSocketUri(URI serverUrl) {
        if (serverUrl == null || serverUrl.getScheme() == null) {
            throw new IllegalArgumentException(
                    "serverUrl must use HTTP or HTTPS"
            );
        }
        String scheme;
        if ("http".equalsIgnoreCase(serverUrl.getScheme())) {
            scheme = "ws";
        } else if ("https".equalsIgnoreCase(serverUrl.getScheme())) {
            scheme = "wss";
        } else {
            throw new IllegalArgumentException(
                    "serverUrl must use HTTP or HTTPS"
            );
        }
        String base = serverUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(
                scheme
                        + base.substring(serverUrl.getScheme().length())
                        + "/api/v1/worker-delivery/websocket"
        );
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
        return value;
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(
                URI uri,
                WebSocketListener listener
        );
    }

    static final class ConnectorResources {

        private final WebSocketConnector connector;
        private final Runnable close;

        ConnectorResources(
                WebSocketConnector connector,
                Runnable close
        ) {
            this.connector = connector;
            this.close = close;
        }
    }

    private final class TransportListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            handleOpen(webSocket, response);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleText(webSocket, text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleBinary(webSocket, bytes);
        }

        @Override
        public void onClosing(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            handleClosing(webSocket, code, reason);
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            handleClosed(webSocket, code, reason);
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            handleFailure(webSocket, error, response);
        }
    }
}
