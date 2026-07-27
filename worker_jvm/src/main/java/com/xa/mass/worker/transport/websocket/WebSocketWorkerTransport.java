package com.xa.mass.worker.transport.websocket;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.execution.WorkerProtocolException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WebSocketWorkerTransport
        implements WebSocket.Listener, AutoCloseable {

    private static final int BAD_DATA = 1007;
    private static final int UNSUPPORTED_DATA = 1003;
    private final WebSocketConnector connector;
    private final URI socketUri;
    private final Duration reconnectInterval;
    private final WorkerDeliveryCodec codec;
    private final WorkerCommandProcessor processor;
    private final ScheduledExecutorService scheduler;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final StringBuilder textFragments = new StringBuilder();
    private boolean running;
    private boolean reconnectScheduled;
    private WebSocket socket;
    private SeedResult pendingResult;

    public WebSocketWorkerTransport(
            URI serverUrl,
            String workerId,
            Duration requestTimeout,
            Duration reconnectInterval,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this(
                connector(
                        HttpClient.newBuilder()
                                .connectTimeout(requestTimeout)
                                .build(),
                        requestTimeout
                ),
                serverUrl,
                workerId,
                reconnectInterval,
                codec,
                processor
        );
    }

    WebSocketWorkerTransport(
            WebSocketConnector connector,
            URI serverUrl,
            String workerId,
            Duration reconnectInterval,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this.connector = connector;
        this.reconnectInterval = reconnectInterval;
        this.codec = codec;
        this.processor = processor;
        socketUri = workerSocketUri(serverUrl, workerId);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-websocket-transport"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
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

    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        if (!running) {
            webSocket.abort();
            return;
        }
        socket = webSocket;
        reconnectScheduled = false;
        if (pendingResult == null) {
            webSocket.request(1);
        } else {
            sendPending(webSocket);
        }
    }

    @Override
    public CompletionStage<?> onText(
            WebSocket webSocket,
            CharSequence data,
            boolean last
    ) {
        synchronized (this) {
            if (!running || webSocket != socket) {
                return CompletableFuture.completedFuture(null);
            }
            if (pendingResult != null) {
                closeProtocolError(webSocket, BAD_DATA);
                return CompletableFuture.completedFuture(null);
            }
            textFragments.append(data);
            if (!last) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            String value = textFragments.toString();
            textFragments.setLength(0);
            WorkerCommandEnvelope command = codec.decodeWorkerCommand(value);
            if (command == null) {
                closeProtocolError(webSocket, BAD_DATA);
                return CompletableFuture.completedFuture(null);
            }
            try {
                var result = processor.process(command);
                if (result.isEmpty()) {
                    webSocket.request(1);
                } else {
                    pendingResult = result.orElseThrow();
                    sendPending(webSocket);
                }
            } catch (WorkerProtocolException error) {
                closeProtocolError(webSocket, BAD_DATA);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(
            WebSocket webSocket,
            ByteBuffer data,
            boolean last
    ) {
        synchronized (this) {
            closeProtocolError(webSocket, UNSUPPORTED_DATA);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(
            WebSocket webSocket,
            int statusCode,
            String reason
    ) {
        synchronized (this) {
            if (webSocket == socket) {
                socket = null;
                textFragments.setLength(0);
                scheduleReconnect();
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        synchronized (this) {
            if (webSocket == socket) {
                socket = null;
                textFragments.setLength(0);
                webSocket.abort();
                scheduleReconnect();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        reconnectScheduled = false;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "Worker stopped"
            );
        }
        scheduler.shutdownNow();
        stopped.countDown();
    }

    public synchronized boolean hasPendingResult() {
        return pendingResult != null;
    }

    public URI socketUri() {
        return socketUri;
    }

    private void connect() {
        try {
            connector.connect(socketUri, this)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            synchronized (WebSocketWorkerTransport.this) {
                                scheduleReconnect();
                            }
                        }
                    });
        } catch (RuntimeException error) {
            scheduleReconnect();
        }
    }

    private void sendPending(WebSocket webSocket) {
        SeedResult sending = pendingResult;
        try {
            webSocket.sendText(codec.encodeSeedResult(sending), true)
                    .whenComplete((ignored, error) -> {
                        synchronized (WebSocketWorkerTransport.this) {
                            if (!running || webSocket != socket) {
                                return;
                            }
                            if (error == null) {
                                if (pendingResult == sending) {
                                    pendingResult = null;
                                }
                                webSocket.request(1);
                            } else {
                                socket = null;
                                webSocket.abort();
                                scheduleReconnect();
                            }
                        }
                    });
        } catch (RuntimeException error) {
            if (webSocket == socket) {
                socket = null;
            }
            webSocket.abort();
            scheduleReconnect();
        }
    }

    private void closeProtocolError(WebSocket webSocket, int code) {
        if (webSocket == socket) {
            socket = null;
        }
        textFragments.setLength(0);
        try {
            webSocket.sendClose(code, "Invalid Worker Delivery message");
        } finally {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        scheduler.schedule(
                () -> {
                    synchronized (WebSocketWorkerTransport.this) {
                        reconnectScheduled = false;
                        if (running && socket == null) {
                            connect();
                        }
                    }
                },
                reconnectInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private static URI workerSocketUri(URI serverUrl, String workerId) {
        String scheme = switch (serverUrl.getScheme()) {
            case "http" -> "ws";
            case "https" -> "wss";
            default -> throw new IllegalArgumentException(
                    "serverUrl must use HTTP or HTTPS"
            );
        };
        String base = serverUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String httpScheme = serverUrl.getScheme();
        return URI.create(
                scheme
                        + base.substring(httpScheme.length())
                        + "/api/v1/worker-delivery/websocket/workers/"
                        + URLEncoder.encode(
                                workerId,
                                StandardCharsets.UTF_8
                        ).replace("+", "%20")
        );
    }

    private static WebSocketConnector connector(
            HttpClient http,
            Duration requestTimeout
    ) {
        return (uri, listener) -> http.newWebSocketBuilder()
                .connectTimeout(requestTimeout)
                .buildAsync(uri, listener);
    }

    @FunctionalInterface
    interface WebSocketConnector {

        CompletableFuture<WebSocket> connect(
                URI uri,
                WebSocket.Listener listener
        );
    }
}
