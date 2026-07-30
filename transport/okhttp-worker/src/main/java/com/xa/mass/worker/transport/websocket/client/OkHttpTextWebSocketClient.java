package com.xa.mass.worker.transport.websocket.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class OkHttpTextWebSocketClient
        implements TextWebSocketClient {

    private final Object lock = new Object();
    private final WebSocketConnector connector;
    private final Runnable closeConnector;
    private final URI socketUri;
    private final Duration reconnectInterval;
    private final ScheduledExecutorService reconnectScheduler;

    private Listener listener;
    private ConnectionAttempt activeAttempt;
    private boolean running;
    private boolean closed;
    private boolean connected;
    private boolean reconnectScheduled;

    public OkHttpTextWebSocketClient(
            URI serverUrl,
            Duration requestTimeout,
            Duration reconnectInterval
    ) {
        this(
                clientConnector(requestTimeout),
                workerSocketUri(serverUrl),
                reconnectInterval
        );
    }

    OkHttpTextWebSocketClient(
            ConnectorResources resources,
            URI socketUri,
            Duration reconnectInterval
    ) {
        Objects.requireNonNull(resources, "resources");
        connector = Objects.requireNonNull(
                resources.connector,
                "connector"
        );
        closeConnector = Objects.requireNonNull(
                resources.close,
                "close"
        );
        this.socketUri = requireWebSocketUri(socketUri);
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        reconnectScheduler = Executors
                .newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "worker-websocket-reconnect"
                    );
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "OkHttpTextWebSocketClient is closed"
                );
            }
            if (running) {
                return;
            }
            this.listener = listener;
            running = true;
        }
        connect();
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        ConnectionAttempt attempt;
        WebSocket socket;
        synchronized (lock) {
            attempt = activeAttempt;
            if (!running
                    || closed
                    || !connected
                    || attempt == null
                    || attempt.socket == null) {
                return false;
            }
            socket = attempt.socket;
        }

        boolean accepted;
        Throwable failure = null;
        try {
            accepted = socket.send(message);
        } catch (RuntimeException error) {
            accepted = false;
            failure = error;
        }
        if (!accepted) {
            socket.cancel();
            disconnect(attempt, failure);
        }
        return accepted;
    }

    @Override
    public void closeCurrent(int code, String reason) {
        ConnectionAttempt attempt;
        synchronized (lock) {
            attempt = activeAttempt;
        }
        if (attempt == null) {
            return;
        }
        WebSocket socket = attempt.socket;
        if (socket != null) {
            boolean accepted;
            try {
                accepted = socket.close(code, reason);
            } catch (RuntimeException error) {
                accepted = false;
            }
            if (!accepted) {
                socket.cancel();
            }
        }
        disconnect(attempt, null);
    }

    @Override
    public boolean isConnected() {
        synchronized (lock) {
            return running && !closed && connected;
        }
    }

    @Override
    public void close() {
        ConnectionAttempt attempt;
        Listener callback;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            connected = false;
            reconnectScheduled = false;
            attempt = activeAttempt;
            activeAttempt = null;
            callback = listener;
        }
        if (attempt != null && attempt.socket != null) {
            attempt.socket.close(1000, "Worker stopped");
            attempt.socket.cancel();
        }
        if (attempt != null && callback != null) {
            callback.onDisconnected();
        }
        reconnectScheduler.shutdownNow();
        closeConnector.run();
    }

    private void connect() {
        ConnectionAttempt attempt;
        synchronized (lock) {
            if (!running || closed || activeAttempt != null) {
                return;
            }
            attempt = new ConnectionAttempt();
            activeAttempt = attempt;
        }

        try {
            WebSocket socket = connector.connect(socketUri, attempt);
            synchronized (lock) {
                if (activeAttempt == attempt
                        && attempt.socket == null) {
                    attempt.socket = socket;
                } else if (activeAttempt != attempt) {
                    socket.cancel();
                }
            }
        } catch (RuntimeException error) {
            disconnect(attempt, error);
        }
    }

    private void disconnect(
            ConnectionAttempt attempt,
            Throwable failure
    ) {
        Listener callback;
        synchronized (lock) {
            if (activeAttempt != attempt) {
                return;
            }
            activeAttempt = null;
            connected = false;
            callback = listener;
        }
        if (callback != null) {
            if (failure != null) {
                callback.onFailure(failure);
            }
            callback.onDisconnected();
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        synchronized (lock) {
            if (!running
                    || closed
                    || reconnectScheduled
                    || activeAttempt != null) {
                return;
            }
            reconnectScheduled = true;
        }
        try {
            reconnectScheduler.schedule(
                    () -> {
                        synchronized (lock) {
                            reconnectScheduled = false;
                            if (!running
                                    || closed
                                    || activeAttempt != null) {
                                return;
                            }
                        }
                        connect();
                    },
                    reconnectInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            synchronized (lock) {
                reconnectScheduled = false;
            }
        }
    }

    private final class ConnectionAttempt
            extends WebSocketListener {

        private volatile WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Listener callback;
            synchronized (lock) {
                if (activeAttempt != this || !running || closed) {
                    webSocket.cancel();
                    return;
                }
                socket = webSocket;
                connected = true;
                reconnectScheduled = false;
                callback = listener;
            }
            callback.onOpen();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Listener callback;
            synchronized (lock) {
                if (activeAttempt != this || !connected) {
                    return;
                }
                callback = listener;
            }
            callback.onText(text);
        }

        @Override
        public void onMessage(
                WebSocket webSocket,
                ByteString bytes
        ) {
            Listener callback;
            synchronized (lock) {
                if (activeAttempt != this || !connected) {
                    return;
                }
                callback = listener;
            }
            callback.onBinary();
        }

        @Override
        public void onClosing(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            webSocket.close(code, reason);
            disconnect(this, null);
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            disconnect(this, null);
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            disconnect(this, error);
        }
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

    private static ConnectorResources clientConnector(Duration timeout) {
        long millis = requirePositive(
                timeout,
                "requestTimeout"
        ).toMillis();
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

    private static URI requireWebSocketUri(URI value) {
        if (value == null
                || (!"ws".equalsIgnoreCase(value.getScheme())
                && !"wss".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    "socketUri must use WS or WSS"
            );
        }
        return value;
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
}
