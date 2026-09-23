package com.xa.mass.worker.android;

import android.os.Handler;
import android.os.Looper;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.net.URI;
import java.util.Objects;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Android WebSocket client using a Host-owned network Looper.
 */
final class AndroidOkHttpTextWebSocketClient
        implements TextMessageClient {

    private static final int NORMAL_CLOSE = 1000;
    private static final int UNSUPPORTED_DATA = 1003;
    private static final int INVALID_DATA = 1007;
    private static final int INTERNAL_FAILURE = 1011;

    private final Object stateLock = new Object();
    private final Object callbackGate = new Object();
    private final WebSocketConnector connector;
    private final Handler handler;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;

    private Listener listener;
    private ConnectionAttempt currentAttempt;
    private int unstableAttempts;
    private boolean running;
    private boolean closed;
    private boolean endpointTerminated;

    AndroidOkHttpTextWebSocketClient(
            OkHttpClient httpClient,
            Looper networkLooper,
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
                networkLooper,
                socketUri,
                reconnectPolicy
        );
    }

    AndroidOkHttpTextWebSocketClient(
            WebSocketConnector connector,
            Looper networkLooper,
            URI socketUri,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this.connector = Objects.requireNonNull(connector, "connector");
        handler = new Handler(Objects.requireNonNull(
                networkLooper,
                "networkLooper"
        ));
        this.socketUri = requireWebSocketUri(socketUri);
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
    }

    @Override
    public void start(Listener value) {
        Objects.requireNonNull(value, "listener");
        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException(
                        "AndroidOkHttpTextWebSocketClient is closed"
                );
            }
            if (running) {
                return;
            }
            listener = value;
            running = true;
        }
        if (!handler.post(this::connect)) {
            synchronized (stateLock) {
                running = false;
                listener = null;
            }
            throw new IllegalStateException(
                    "Unable to start Android WebSocket client"
            );
        }
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        WebSocket socket;
        synchronized (stateLock) {
            ConnectionAttempt attempt = currentAttempt;
            if (!running
                    || closed
                    || attempt == null
                    || !attempt.open
                    || attempt.finished) {
                return false;
            }
            socket = attempt.socket;
        }
        try {
            return socket != null && socket.send(message);
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public void closeCurrent(CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        ConnectionAttempt attempt;
        synchronized (stateLock) {
            attempt = currentAttempt;
            if (!running || closed || attempt == null) {
                return;
            }
        }
        closeSocket(
                attempt.socket,
                closeCode(reason),
                closeMessage(reason)
        );
        finishAttempt(attempt);
    }

    @Override
    public void close() {
        ConnectionAttempt attempt;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            attempt = currentAttempt;
            currentAttempt = null;
            if (attempt != null) {
                attempt.finished = true;
                attempt.open = false;
            }
        }
        handler.removeCallbacksAndMessages(null);
        closeAndCancel(attempt == null ? null : attempt.socket);
        synchronized (callbackGate) {
            synchronized (stateLock) {
                listener = null;
            }
        }
    }

    private void connect() {
        ConnectionAttempt attempt;
        synchronized (stateLock) {
            if (!running || closed || currentAttempt != null) {
                return;
            }
            attempt = new ConnectionAttempt();
            currentAttempt = attempt;
        }
        try {
            WebSocket socket = Objects.requireNonNull(
                    connector.connect(
                            socketUri,
                            new ConnectionListener(attempt)
                    ),
                    "connector returned null"
            );
            synchronized (stateLock) {
                if (currentAttempt == attempt && !attempt.finished) {
                    if (attempt.socket == null) {
                        attempt.socket = socket;
                    }
                } else {
                    socket.cancel();
                }
            }
        } catch (RuntimeException error) {
            finishAttempt(attempt);
        }
    }

    private void opened(
            ConnectionAttempt attempt,
            WebSocket socket
    ) {
        synchronized (stateLock) {
            if (!isCurrentLocked(attempt)) {
                socket.cancel();
                return;
            }
            attempt.socket = socket;
            attempt.open = true;
        }
        scheduleStable(attempt);
        emit(attempt, Listener::onOpen);
    }

    private void message(ConnectionAttempt attempt, String text) {
        emit(attempt, callback -> callback.onMessage(text));
    }

    private void binary(ConnectionAttempt attempt) {
        synchronized (stateLock) {
            if (!isOpenLocked(attempt)) {
                return;
            }
        }
        closeSocket(
                attempt.socket,
                UNSUPPORTED_DATA,
                "Text messages only"
        );
        finishAttempt(attempt);
    }

    private void closing(
            ConnectionAttempt attempt,
            WebSocket socket,
            int code,
            String reason
    ) {
        synchronized (stateLock) {
            if (!isCurrentLocked(attempt)) {
                return;
            }
        }
        if (!closeSocket(socket, code, reason)) {
            finishAttempt(attempt);
        }
    }

    private void finishAttempt(ConnectionAttempt attempt) {
        boolean terminate;
        synchronized (stateLock) {
            if (!isCurrentLocked(attempt)) {
                return;
            }
            attempt.finished = true;
            attempt.open = false;
            currentAttempt = null;
            if (!running || closed) {
                return;
            }
            unstableAttempts++;
            terminate = unstableAttempts
                    >= reconnectPolicy.maxUnstableAttempts();
            if (terminate) {
                running = false;
                endpointTerminated = true;
            }
        }
        if (terminate) {
            emitEndpointTerminated();
        } else {
            handler.postDelayed(
                    this::connect,
                    reconnectPolicy.reconnectInterval().toMillis()
            );
        }
    }

    private void scheduleStable(ConnectionAttempt attempt) {
        handler.postDelayed(
                () -> {
                    synchronized (stateLock) {
                        if (isOpenLocked(attempt)) {
                            unstableAttempts = 0;
                        }
                    }
                },
                reconnectPolicy.stableConnectionDuration().toMillis()
        );
    }

    private void emit(
            ConnectionAttempt attempt,
            ListenerCallback callback
    ) {
        synchronized (callbackGate) {
            Listener current;
            synchronized (stateLock) {
                if (!isOpenLocked(attempt)) {
                    return;
                }
                current = listener;
            }
            if (current != null) {
                callback.invoke(current);
            }
        }
    }

    private void emitEndpointTerminated() {
        synchronized (callbackGate) {
            Listener current;
            synchronized (stateLock) {
                if (closed || !endpointTerminated) {
                    return;
                }
                current = listener;
            }
            if (current != null) {
                current.onEndpointTerminated();
            }
        }
    }

    private boolean isCurrentLocked(ConnectionAttempt attempt) {
        return !closed
                && running
                && currentAttempt == attempt
                && !attempt.finished;
    }

    private boolean isOpenLocked(ConnectionAttempt attempt) {
        return isCurrentLocked(attempt) && attempt.open;
    }

    private final class ConnectionListener extends WebSocketListener {

        private final ConnectionAttempt attempt;

        private ConnectionListener(ConnectionAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            opened(attempt, webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            message(attempt, text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            binary(attempt);
        }

        @Override
        public void onClosing(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            closing(attempt, webSocket, code, reason);
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            finishAttempt(attempt);
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            finishAttempt(attempt);
        }
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(URI uri, WebSocketListener listener);
    }

    private static final class ConnectionAttempt {

        private WebSocket socket;
        private boolean open;
        private boolean finished;
    }

    @FunctionalInterface
    private interface ListenerCallback {

        void invoke(Listener listener);
    }

    private static boolean closeSocket(
            WebSocket socket,
            int code,
            String reason
    ) {
        if (socket == null) {
            return false;
        }
        try {
            if (socket.close(code, reason)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Cancel below when the close handshake cannot start.
        }
        socket.cancel();
        return false;
    }

    private static void closeAndCancel(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close(NORMAL_CLOSE, "Worker stopped");
        } finally {
            socket.cancel();
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
}
