package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.TextMessageReconnectState;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class JavaOkHttpTextWebSocketClient
        implements TextMessageClient {

    private static final int NORMAL_CLOSE = 1000;
    private static final int UNSUPPORTED_DATA = 1003;
    private static final int INVALID_DATA = 1007;
    private static final int INTERNAL_FAILURE = 1011;

    private final Object lock = new Object();
    private final WebSocketConnector connector;
    private final ScheduledExecutorService networkExecutor;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final TextMessageReconnectState reconnectState;

    private Listener listener;
    private ConnectionAttempt activeAttempt;
    private boolean running;
    private boolean closed;
    private boolean connected;
    private boolean reconnectScheduled;
    private boolean endpointNotified;

    JavaOkHttpTextWebSocketClient(
            OkHttpClient httpClient,
            ScheduledExecutorService networkExecutor,
            URI socketUri,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this(
                (uri, listener) -> Objects.requireNonNull(
                        httpClient,
                        "httpClient"
                ).newWebSocket(
                        new Request.Builder()
                                .url(uri.toString())
                                .build(),
                        listener
                ),
                networkExecutor,
                socketUri,
                reconnectPolicy
        );
    }

    JavaOkHttpTextWebSocketClient(
            WebSocketConnector connector,
            ScheduledExecutorService networkExecutor,
            URI socketUri,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.networkExecutor = Objects.requireNonNull(
                networkExecutor,
                "networkExecutor"
        );
        this.socketUri = requireWebSocketUri(socketUri);
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
        reconnectState = new TextMessageReconnectState(reconnectPolicy);
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "JavaOkHttpTextWebSocketClient is closed"
                );
            }
            if (running) {
                return;
            }
            this.listener = listener;
            running = true;
        }
        execute(this::connect);
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        WebSocket socket;
        synchronized (lock) {
            if (!running
                    || closed
                    || !connected
                    || activeAttempt == null
                    || activeAttempt.socket == null) {
                return false;
            }
            socket = activeAttempt.socket;
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
        execute(() -> closeCurrentOnNetworkThread(reason));
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
            attempt = activeAttempt;
            activeAttempt = null;
            listener = null;
            reconnectState.close();
        }
        if (attempt != null && attempt.socket != null) {
            try {
                attempt.socket.close(NORMAL_CLOSE, "Worker stopped");
            } finally {
                attempt.socket.cancel();
            }
        }
    }

    private void connect() {
        ConnectionAttempt attempt;
        synchronized (lock) {
            if (!running || closed || activeAttempt != null) {
                return;
            }
            long generation = reconnectState.beginAttempt();
            attempt = new ConnectionAttempt(generation);
            activeAttempt = attempt;
            reconnectScheduled = false;
        }
        try {
            WebSocket socket = connector.connect(
                    socketUri,
                    attempt.listener
            );
            synchronized (lock) {
                if (activeAttempt == attempt && attempt.socket == null) {
                    attempt.socket = socket;
                } else if (activeAttempt != attempt) {
                    socket.cancel();
                }
            }
        } catch (RuntimeException error) {
            disconnect(attempt);
        }
    }

    private void closeCurrentOnNetworkThread(CloseReason reason) {
        ConnectionAttempt attempt;
        synchronized (lock) {
            attempt = activeAttempt;
            if (!running || closed || attempt == null) {
                return;
            }
        }
        WebSocket socket = attempt.socket;
        if (socket != null) {
            boolean accepted;
            try {
                accepted = socket.close(
                        closeCode(reason),
                        closeMessage(reason)
                );
            } catch (RuntimeException error) {
                accepted = false;
            }
            if (!accepted) {
                socket.cancel();
            }
        }
        disconnect(attempt);
    }

    private void opened(ConnectionAttempt attempt, WebSocket socket) {
        Listener callback;
        synchronized (lock) {
            if (activeAttempt != attempt
                    || !running
                    || closed
                    || !reconnectState.opened(attempt.generation)) {
                socket.cancel();
                return;
            }
            attempt.socket = socket;
            connected = true;
            callback = listener;
        }
        if (callback != null) {
            callback.onOpen();
        }
        scheduleStable(attempt);
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
        WebSocket socket = attempt.socket;
        if (socket != null) {
            boolean accepted = socket.close(
                    UNSUPPORTED_DATA,
                    "Text messages only"
            );
            if (!accepted) {
                socket.cancel();
            }
        }
        disconnect(attempt);
    }

    private void disconnect(ConnectionAttempt attempt) {
        Listener callback;
        TextMessageReconnectState.DisconnectAction action;
        synchronized (lock) {
            if (activeAttempt != attempt) {
                return;
            }
            activeAttempt = null;
            connected = false;
            action = reconnectState.disconnected(attempt.generation);
            callback = listener;
            if (action
                    == TextMessageReconnectState.DisconnectAction.TERMINATE) {
                endpointNotified = true;
            }
        }
        if (action
                == TextMessageReconnectState.DisconnectAction.TERMINATE) {
            if (callback != null) {
                callback.onEndpointTerminated();
            }
        } else if (action
                == TextMessageReconnectState.DisconnectAction.RECONNECT) {
            scheduleReconnect();
        }
    }

    private void scheduleStable(ConnectionAttempt attempt) {
        schedule(
                () -> reconnectState.becameStable(attempt.generation),
                reconnectPolicy.stableConnectionDuration().toMillis()
        );
    }

    private void scheduleReconnect() {
        synchronized (lock) {
            if (!running
                    || closed
                    || endpointNotified
                    || reconnectScheduled
                    || activeAttempt != null) {
                return;
            }
            reconnectScheduled = true;
        }
        schedule(
                () -> {
                    synchronized (lock) {
                        reconnectScheduled = false;
                        if (!running || closed || activeAttempt != null) {
                            return;
                        }
                    }
                    connect();
                },
                reconnectPolicy.reconnectInterval().toMillis()
        );
    }

    private void execute(Runnable action) {
        try {
            networkExecutor.execute(action);
        } catch (RejectedExecutionException ignored) {
            // Host resource shutdown is terminal for borrowed clients.
        }
    }

    private void schedule(Runnable action, long delayMillis) {
        try {
            networkExecutor.schedule(
                    action,
                    delayMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // Host resource shutdown is terminal for borrowed clients.
        }
    }

    private final class ConnectionAttempt {

        private final long generation;
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
                    execute(() -> disconnect(ConnectionAttempt.this));
                }
            }

            @Override
            public void onClosed(
                    WebSocket webSocket,
                    int code,
                    String reason
            ) {
                execute(() -> disconnect(ConnectionAttempt.this));
            }

            @Override
            public void onFailure(
                    WebSocket webSocket,
                    Throwable error,
                    Response response
            ) {
                execute(() -> disconnect(ConnectionAttempt.this));
            }
        };

        private WebSocket socket;

        private ConnectionAttempt(long generation) {
            this.generation = generation;
        }
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(URI uri, WebSocketListener listener);
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
}
