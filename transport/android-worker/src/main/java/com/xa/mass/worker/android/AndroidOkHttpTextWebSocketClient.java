package com.xa.mass.worker.android;

import android.os.Handler;
import android.os.Looper;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    private final WebSocketConnector connector;
    private final Handler handler;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<WebSocket> openSocket =
            new AtomicReference<>();

    // Host network Looper-owned state.
    private Listener listener;
    private ConnectionAttempt currentAttempt;
    private int unstableAttempts;
    private Runnable stableTask;

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
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            throw new IllegalStateException(
                    "AndroidOkHttpTextWebSocketClient is closed"
            );
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (!handler.post(() -> startOnNetworkThread(listener))) {
            closed.set(true);
            throw new IllegalStateException(
                    "Unable to start Android WebSocket client"
            );
        }
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        WebSocket socket = openSocket.get();
        if (closed.get() || socket == null) {
            return false;
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
        WebSocket socket = openSocket.get();
        if (closed.get() || socket == null) {
            return;
        }
        handler.post(() -> {
            ConnectionAttempt attempt = currentAttempt;
            if (!closed.get()
                    && attempt != null
                    && attempt.socket == socket) {
                closeCurrentOnNetworkThread(
                        attempt,
                        closeCode(reason),
                        closeMessage(reason)
                );
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WebSocket socket = openSocket.getAndSet(null);
        if (socket != null) {
            socket.cancel();
        }
        handler.removeCallbacksAndMessages(null);
        if (Looper.myLooper() == handler.getLooper()) {
            closeOnNetworkThread();
        } else {
            handler.postAtFrontOfQueue(this::closeOnNetworkThread);
        }
    }

    private void startOnNetworkThread(Listener listener) {
        if (closed.get()) {
            return;
        }
        this.listener = listener;
        connectOnNetworkThread();
    }

    private void connectOnNetworkThread() {
        if (closed.get()
                || currentAttempt != null) {
            return;
        }
        ConnectionAttempt attempt = new ConnectionAttempt();
        currentAttempt = attempt;
        try {
            WebSocket socket = Objects.requireNonNull(
                    connector.connect(
                            socketUri,
                            new ConnectionListener(attempt)
                    ),
                    "connector returned null"
            );
            if (isCurrent(attempt)
                    && !closed.get()) {
                attempt.socket = socket;
            } else {
                socket.cancel();
            }
        } catch (RuntimeException error) {
            finishAttempt(attempt);
        }
    }

    private void openOnNetworkThread(
            ConnectionAttempt attempt,
            WebSocket socket
    ) {
        if (!isCurrent(attempt)
                || closed.get()) {
            socket.cancel();
            return;
        }
        attempt.socket = socket;
        openSocket.set(socket);
        if (closed.get()) {
            openSocket.compareAndSet(socket, null);
            socket.cancel();
            return;
        }
        Listener callback = listener;
        if (callback != null) {
            callback.onOpen();
        }
        if (isOpen(attempt)) {
            scheduleStable(attempt);
        }
    }

    private void textOnNetworkThread(
            ConnectionAttempt attempt,
            String message
    ) {
        if (!isOpen(attempt)) {
            return;
        }
        Listener callback = listener;
        if (callback != null) {
            callback.onMessage(message);
        }
    }

    private void binaryOnNetworkThread(ConnectionAttempt attempt) {
        if (!isOpen(attempt)) {
            return;
        }
        closeCurrentOnNetworkThread(
                attempt,
                UNSUPPORTED_DATA,
                "Text messages only"
        );
    }

    private void closingOnNetworkThread(
            ConnectionAttempt attempt,
            WebSocket socket,
            int code,
            String reason
    ) {
        if (isCurrent(attempt)
                && !closeSocket(socket, code, reason)) {
            finishAttempt(attempt);
        }
    }

    private void closeCurrentOnNetworkThread(
            ConnectionAttempt attempt,
            int code,
            String reason
    ) {
        if (!isOpen(attempt)) {
            return;
        }
        closeSocket(attempt.socket, code, reason);
        finishAttempt(attempt);
    }

    private void finishAttempt(ConnectionAttempt attempt) {
        if (!isCurrent(attempt)) {
            return;
        }
        cancelStable();
        currentAttempt = null;
        WebSocket socket = attempt.socket;
        if (socket != null) {
            openSocket.compareAndSet(socket, null);
        }
        if (closed.get()) {
            return;
        }
        unstableAttempts++;
        if (unstableAttempts
                >= reconnectPolicy.maxUnstableAttempts()) {
            Listener callback = listener;
            if (callback != null) {
                callback.onEndpointTerminated();
            }
            return;
        }
        scheduleReconnect();
    }

    private void scheduleStable(ConnectionAttempt attempt) {
        cancelStable();
        stableTask = () -> {
            stableTask = null;
            if (isOpen(attempt)) {
                unstableAttempts = 0;
            }
        };
        handler.postDelayed(
                stableTask,
                reconnectPolicy.stableConnectionDuration().toMillis()
        );
    }

    private void cancelStable() {
        if (stableTask != null) {
            handler.removeCallbacks(stableTask);
            stableTask = null;
        }
    }

    private void scheduleReconnect() {
        handler.postDelayed(
                () -> {
                    if (!closed.get()
                            && currentAttempt == null) {
                        connectOnNetworkThread();
                    }
                },
                reconnectPolicy.reconnectInterval().toMillis()
        );
    }

    private void closeOnNetworkThread() {
        cancelStable();
        ConnectionAttempt attempt = currentAttempt;
        currentAttempt = null;
        openSocket.set(null);
        listener = null;
        if (attempt != null && attempt.socket != null) {
            try {
                attempt.socket.close(NORMAL_CLOSE, "Client closed");
            } finally {
                attempt.socket.cancel();
            }
        }
    }

    private void postConnectionCallback(
            ConnectionAttempt attempt,
            Runnable callback
    ) {
        if (!closed.get()) {
            handler.post(() -> {
                if (!closed.get()
                        && isCurrent(attempt)) {
                    callback.run();
                }
            });
        }
    }

    private boolean isCurrent(ConnectionAttempt attempt) {
        return currentAttempt == attempt;
    }

    private boolean isOpen(ConnectionAttempt attempt) {
        return !closed.get()
                && isCurrent(attempt)
                && openSocket.get() == attempt.socket;
    }

    private final class ConnectionListener extends WebSocketListener {

        private final ConnectionAttempt attempt;

        private ConnectionListener(ConnectionAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            postConnectionCallback(
                    attempt,
                    () -> openOnNetworkThread(attempt, webSocket)
            );
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            postConnectionCallback(
                    attempt,
                    () -> textOnNetworkThread(attempt, text)
            );
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            postConnectionCallback(
                    attempt,
                    () -> binaryOnNetworkThread(attempt)
            );
        }

        @Override
        public void onClosing(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            postConnectionCallback(
                    attempt,
                    () -> closingOnNetworkThread(
                            attempt,
                            webSocket,
                            code,
                            reason
                    )
            );
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            postConnectionCallback(
                    attempt,
                    () -> finishAttempt(attempt)
            );
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            postConnectionCallback(
                    attempt,
                    () -> finishAttempt(attempt)
            );
        }
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(URI uri, WebSocketListener listener);
    }

    private static final class ConnectionAttempt {

        private WebSocket socket;
    }

    private static boolean closeSocket(
            WebSocket socket,
            int code,
            String reason
    ) {
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
