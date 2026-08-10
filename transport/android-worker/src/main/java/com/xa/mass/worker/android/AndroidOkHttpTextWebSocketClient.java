package com.xa.mass.worker.android;

import android.os.Handler;
import android.os.Looper;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.TextMessageReconnectState;

import java.net.URI;
import java.util.Objects;
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

    private final Object lifecycleLock = new Object();
    private final WebSocketConnector connector;
    private final Handler handler;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final TextMessageReconnectState reconnectState;
    private final AtomicReference<ActiveConnection> activeConnection =
            new AtomicReference<>();

    private volatile boolean closeRequested;
    private boolean started;

    // Host network Looper-owned state.
    private Listener listener;
    private ConnectionAttempt currentAttempt;
    private boolean reconnectScheduled;
    private boolean endpointNotified;
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
        reconnectState = new TextMessageReconnectState(reconnectPolicy);
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            if (closeRequested) {
                throw new IllegalStateException(
                        "AndroidOkHttpTextWebSocketClient is closed"
                );
            }
            if (started) {
                return;
            }
            started = true;
        }
        if (!handler.post(() -> startOnNetworkThread(listener))) {
            close();
            throw new IllegalStateException(
                    "Unable to start Android WebSocket client"
            );
        }
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        ActiveConnection active = activeConnection.get();
        if (closeRequested || active == null) {
            return false;
        }
        try {
            return active.socket.send(message);
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public void closeCurrent(CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        ActiveConnection active = activeConnection.get();
        if (active == null || closeRequested) {
            return;
        }
        handler.post(() -> closeCurrentOnNetworkThread(
                active.generation,
                closeCode(reason),
                closeMessage(reason)
        ));
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
        }
        ActiveConnection active = activeConnection.getAndSet(null);
        if (active != null) {
            active.socket.cancel();
        }
        handler.removeCallbacksAndMessages(null);
        if (Looper.myLooper() == handler.getLooper()) {
            closeOnNetworkThread();
        } else {
            handler.postAtFrontOfQueue(this::closeOnNetworkThread);
        }
    }

    private void startOnNetworkThread(Listener listener) {
        if (closeRequested) {
            return;
        }
        this.listener = listener;
        connectOnNetworkThread();
    }

    private void connectOnNetworkThread() {
        if (closeRequested
                || currentAttempt != null
                || endpointNotified) {
            return;
        }
        reconnectScheduled = false;
        long generation;
        try {
            generation = reconnectState.beginAttempt();
        } catch (IllegalStateException terminal) {
            return;
        }
        ConnectionAttempt attempt = new ConnectionAttempt(generation);
        currentAttempt = attempt;
        try {
            WebSocket socket = connector.connect(
                    socketUri,
                    new ConnectionListener(generation)
            );
            if (isCurrent(generation)) {
                attempt.socket = socket;
            } else {
                socket.cancel();
            }
        } catch (RuntimeException error) {
            disconnectOnNetworkThread(generation);
        }
    }

    private void openOnNetworkThread(
            long generation,
            WebSocket socket
    ) {
        ConnectionAttempt attempt = requireCurrent(generation);
        if (attempt == null
                || closeRequested
                || !reconnectState.opened(generation)) {
            socket.cancel();
            return;
        }
        attempt.socket = socket;
        activeConnection.set(
                new ActiveConnection(generation, socket)
        );
        Listener callback = listener;
        if (callback != null) {
            callback.onOpen();
        }
        scheduleStable(generation);
    }

    private void textOnNetworkThread(long generation, String message) {
        if (!isConnectedGeneration(generation)) {
            return;
        }
        Listener callback = listener;
        if (callback != null) {
            callback.onMessage(message);
        }
    }

    private void binaryOnNetworkThread(long generation) {
        if (!isConnectedGeneration(generation)) {
            return;
        }
        closeCurrentOnNetworkThread(
                generation,
                UNSUPPORTED_DATA,
                "Text messages only"
        );
    }

    private void closeCurrentOnNetworkThread(
            long generation,
            int code,
            String reason
    ) {
        ConnectionAttempt attempt = requireCurrent(generation);
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
        disconnectOnNetworkThread(generation);
    }

    private void disconnectOnNetworkThread(long generation) {
        ConnectionAttempt attempt = requireCurrent(generation);
        if (attempt == null) {
            return;
        }
        cancelStable();
        currentAttempt = null;
        ActiveConnection active = activeConnection.get();
        if (active != null && active.generation == generation) {
            activeConnection.compareAndSet(active, null);
        }

        TextMessageReconnectState.DisconnectAction action =
                reconnectState.disconnected(generation);
        if (action
                == TextMessageReconnectState.DisconnectAction.TERMINATE) {
            endpointNotified = true;
            Listener callback = listener;
            if (!closeRequested && callback != null) {
                callback.onEndpointTerminated();
            }
        } else if (action
                == TextMessageReconnectState.DisconnectAction.RECONNECT) {
            scheduleReconnect();
        }
    }

    private void scheduleStable(long generation) {
        cancelStable();
        stableTask = () -> {
            reconnectState.becameStable(generation);
            stableTask = null;
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
        if (closeRequested || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        handler.postDelayed(
                this::connectOnNetworkThread,
                reconnectPolicy.reconnectInterval().toMillis()
        );
    }

    private void closeOnNetworkThread() {
        cancelStable();
        reconnectState.close();
        ConnectionAttempt attempt = currentAttempt;
        currentAttempt = null;
        activeConnection.set(null);
        listener = null;
        reconnectScheduled = false;
        if (attempt != null && attempt.socket != null) {
            try {
                attempt.socket.close(NORMAL_CLOSE, "Client closed");
            } finally {
                attempt.socket.cancel();
            }
        }
    }

    private void postConnectionCallback(
            long generation,
            Runnable callback
    ) {
        if (!closeRequested) {
            handler.post(() -> {
                if (!closeRequested && isCurrent(generation)) {
                    callback.run();
                }
            });
        }
    }

    private boolean isCurrent(long generation) {
        ConnectionAttempt attempt = currentAttempt;
        return attempt != null && attempt.generation == generation;
    }

    private ConnectionAttempt requireCurrent(long generation) {
        return isCurrent(generation) ? currentAttempt : null;
    }

    private boolean isConnectedGeneration(long generation) {
        ActiveConnection active = activeConnection.get();
        return isCurrent(generation)
                && active != null
                && active.generation == generation;
    }

    private final class ConnectionListener extends WebSocketListener {

        private final long generation;

        private ConnectionListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            postConnectionCallback(
                    generation,
                    () -> openOnNetworkThread(generation, webSocket)
            );
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            postConnectionCallback(
                    generation,
                    () -> textOnNetworkThread(generation, text)
            );
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            postConnectionCallback(
                    generation,
                    () -> binaryOnNetworkThread(generation)
            );
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
                postConnectionCallback(
                        generation,
                        () -> disconnectOnNetworkThread(generation)
                );
            }
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            postConnectionCallback(
                    generation,
                    () -> disconnectOnNetworkThread(generation)
            );
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            postConnectionCallback(
                    generation,
                    () -> disconnectOnNetworkThread(generation)
            );
        }
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(URI uri, WebSocketListener listener);
    }

    private static final class ConnectionAttempt {

        private final long generation;
        private WebSocket socket;

        private ConnectionAttempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class ActiveConnection {

        private final long generation;
        private final WebSocket socket;

        private ActiveConnection(long generation, WebSocket socket) {
            this.generation = generation;
            this.socket = socket;
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
