package com.xa.mass.transport.client.okhttp;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
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

public final class OkHttpTextWebSocketClient
        implements TextMessageClient {

    private static final int NORMAL_CLOSE = 1000;
    private static final int UNSUPPORTED_DATA = 1003;
    private static final int INVALID_DATA = 1007;
    private static final int INTERNAL_FAILURE = 1011;

    private final Object lock = new Object();
    private final WebSocketConnector connector;
    private final Runnable closeConnector;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final Duration closeTimeout;
    private final ScheduledExecutorService connectionExecutor;
    private volatile Thread connectionThread;

    private Listener listener;
    private ConnectionAttempt activeAttempt;
    private boolean running;
    private boolean closed;
    private boolean connected;
    private boolean reconnectScheduled;
    private boolean reconnectExhausted;
    private int unstableAttempts;

    public OkHttpTextWebSocketClient(
            URI socketUri,
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this(
                clientConnector(socketUri, requestTimeout),
                socketUri,
                reconnectPolicy,
                requestTimeout
        );
    }

    OkHttpTextWebSocketClient(
            ConnectorResources resources,
            URI socketUri,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this(
                resources,
                socketUri,
                reconnectPolicy,
                Duration.ofSeconds(5)
        );
    }

    private OkHttpTextWebSocketClient(
            ConnectorResources resources,
            URI socketUri,
            TextMessageReconnectPolicy reconnectPolicy,
            Duration closeTimeout
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
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
        this.closeTimeout = requirePositive(closeTimeout, "closeTimeout");
        connectionExecutor = Executors
                .newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "worker-websocket-connection"
                    );
                    thread.setDaemon(true);
                    connectionThread = thread;
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
            reconnectExhausted = false;
            unstableAttempts = 0;
        }
        execute(this::connect);
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

        try {
            return socket.send(message);
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public void closeCurrent(CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        execute(() -> closeCurrentOnConnectionThread(reason));
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
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            connected = false;
            reconnectScheduled = false;
            reconnectExhausted = true;
            attempt = activeAttempt;
            activeAttempt = null;
            listener = null;
        }
        if (attempt != null && attempt.socket != null) {
            try {
                attempt.socket.close(NORMAL_CLOSE, "Worker stopped");
            } finally {
                attempt.socket.cancel();
            }
        }
        connectionExecutor.shutdownNow();
        if (Thread.currentThread() != connectionThread) {
            try {
                connectionExecutor.awaitTermination(
                        closeTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        closeConnector.run();
    }

    private void connect() {
        ConnectionAttempt attempt;
        synchronized (lock) {
            if (!running
                    || closed
                    || reconnectExhausted
                    || activeAttempt != null) {
                return;
            }
            attempt = new ConnectionAttempt();
            activeAttempt = attempt;
        }

        try {
            WebSocket socket = connector.connect(socketUri, attempt.listener);
            synchronized (lock) {
                if (activeAttempt == attempt && attempt.socket == null) {
                    attempt.socket = socket;
                } else if (activeAttempt != attempt) {
                    socket.cancel();
                }
            }
        } catch (RuntimeException error) {
            disconnect(attempt, error);
        }
    }

    private void closeCurrentOnConnectionThread(CloseReason reason) {
        ConnectionAttempt attempt;
        synchronized (lock) {
            attempt = activeAttempt;
            if (!running || closed || attempt == null) {
                return;
            }
        }
        closeSocket(
                attempt,
                closeCode(reason),
                closeMessage(reason)
        );
    }

    private void closeSocket(
            ConnectionAttempt attempt,
            int code,
            String reason
    ) {
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

    private void opened(ConnectionAttempt attempt, WebSocket socket) {
        Listener callback;
        synchronized (lock) {
            if (activeAttempt != attempt || !running || closed) {
                socket.cancel();
                return;
            }
            attempt.socket = socket;
            connected = true;
            reconnectScheduled = false;
            callback = listener;
        }
        if (callback != null) {
            callback.onOpen();
        }
        scheduleStableReset(attempt);
    }

    private void message(ConnectionAttempt attempt, String text) {
        Listener callback;
        synchronized (lock) {
            if (activeAttempt != attempt || !connected || closed) {
                return;
            }
            callback = listener;
        }
        if (callback != null) {
            callback.onMessage(text);
        }
    }

    private void binary(ConnectionAttempt attempt) {
        synchronized (lock) {
            if (activeAttempt != attempt || !connected || closed) {
                return;
            }
        }
        closeSocket(attempt, UNSUPPORTED_DATA, "Text messages only");
    }

    private void disconnect(
            ConnectionAttempt attempt,
            Throwable failure
    ) {
        Listener callback;
        boolean exhausted;
        synchronized (lock) {
            if (activeAttempt != attempt) {
                return;
            }
            activeAttempt = null;
            connected = false;
            callback = listener;
            unstableAttempts++;
            exhausted = unstableAttempts
                    >= reconnectPolicy.maxUnstableAttempts();
            reconnectExhausted = exhausted;
        }
        if (callback != null) {
            if (failure == null) {
                callback.onDisconnected();
            } else {
                callback.onFailure(failure);
            }
            if (exhausted) {
                callback.onReconnectExhausted();
            }
        }
        if (!exhausted) {
            scheduleReconnect();
        }
    }

    private void scheduleStableReset(ConnectionAttempt attempt) {
        try {
            connectionExecutor.schedule(
                    () -> {
                        synchronized (lock) {
                            if (running
                                    && !closed
                                    && connected
                                    && activeAttempt == attempt) {
                                unstableAttempts = 0;
                            }
                        }
                    },
                    reconnectPolicy.stableConnectionDuration().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // Terminal close owns executor shutdown.
        }
    }

    private void scheduleReconnect() {
        synchronized (lock) {
            if (!running
                    || closed
                    || reconnectExhausted
                    || reconnectScheduled
                    || activeAttempt != null) {
                return;
            }
            reconnectScheduled = true;
        }
        try {
            connectionExecutor.schedule(
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
                    reconnectPolicy.reconnectInterval().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            synchronized (lock) {
                reconnectScheduled = false;
            }
        }
    }

    private void execute(Runnable action) {
        try {
            connectionExecutor.execute(action);
        } catch (RejectedExecutionException ignored) {
            // Terminal close owns executor shutdown.
        }
    }

    private final class ConnectionAttempt {

        private final WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                execute(() -> opened(ConnectionAttempt.this, webSocket));
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                execute(() -> message(ConnectionAttempt.this, text));
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                execute(() -> binary(ConnectionAttempt.this));
            }

            @Override
            public void onClosing(
                    WebSocket webSocket,
                    int code,
                    String reason
            ) {
                try {
                    webSocket.close(code, reason);
                } finally {
                    execute(() -> disconnect(ConnectionAttempt.this, null));
                }
            }

            @Override
            public void onClosed(
                    WebSocket webSocket,
                    int code,
                    String reason
            ) {
                execute(() -> disconnect(ConnectionAttempt.this, null));
            }

            @Override
            public void onFailure(
                    WebSocket webSocket,
                    Throwable error,
                    Response response
            ) {
                execute(() -> disconnect(ConnectionAttempt.this, error));
            }
        };

        private WebSocket socket;
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(URI uri, WebSocketListener listener);
    }

    static final class ConnectorResources {

        private final WebSocketConnector connector;
        private final Runnable close;

        ConnectorResources(WebSocketConnector connector, Runnable close) {
            this.connector = connector;
            this.close = close;
        }
    }

    private static ConnectorResources clientConnector(
            URI socketUri,
            Duration timeout
    ) {
        requireWebSocketUri(socketUri);
        long millis = requirePositive(timeout, "requestTimeout").toMillis();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        return new ConnectorResources(
                (uri, listener) -> client.newWebSocket(
                        new Request.Builder().url(uri.toString()).build(),
                        listener
                ),
                () -> {
                    client.dispatcher().cancelAll();
                    client.connectionPool().evictAll();
                    client.dispatcher().executorService().shutdownNow();
                }
        );
    }

    private static int closeCode(CloseReason reason) {
        switch (reason) {
            case NORMAL:
                return NORMAL_CLOSE;
            case PROTOCOL_ERROR:
                return INVALID_DATA;
            case SEND_FAILURE:
                return INTERNAL_FAILURE;
            default:
                throw new IllegalArgumentException("Unknown close reason");
        }
    }

    private static String closeMessage(CloseReason reason) {
        switch (reason) {
            case NORMAL:
                return "Worker stopped";
            case PROTOCOL_ERROR:
                return "Invalid Worker Delivery message";
            case SEND_FAILURE:
                return "Worker Delivery send failed";
            default:
                throw new IllegalArgumentException("Unknown close reason");
        }
    }

    private static URI requireWebSocketUri(URI value) {
        if (value == null
                || value.getHost() == null
                || (!"ws".equalsIgnoreCase(value.getScheme())
                && !"wss".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    "socketUri must be an absolute WS or WSS URI"
            );
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
