package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class JavaOkHttpTextWebSocketClient
        implements TextMessageClient {

    private enum Phase {
        NEW,
        RUNNING,
        TERMINAL
    }

    private static final int NORMAL_CLOSE = 1000;
    private static final int UNSUPPORTED_DATA = 1003;
    private static final int INVALID_DATA = 1007;
    private static final int INTERNAL_FAILURE = 1011;
    private static final long NOT_OPEN = Long.MIN_VALUE;

    private static final ClientState NEW = new ClientState(
            Phase.NEW,
            null,
            null,
            0
    );
    private static final ClientState TERMINAL = new ClientState(
            Phase.TERMINAL,
            null,
            null,
            0
    );

    private final WebSocketConnector connector;
    private final ScheduledExecutorService networkScheduler;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final long stableConnectionNanos;
    private final AtomicReference<ClientState> currentAttempt =
            new AtomicReference<>(NEW);

    JavaOkHttpTextWebSocketClient(
            OkHttpClient httpClient,
            ScheduledExecutorService networkScheduler,
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
                networkScheduler,
                socketUri,
                reconnectPolicy
        );
    }

    JavaOkHttpTextWebSocketClient(
            WebSocketConnector connector,
            ScheduledExecutorService networkScheduler,
            URI socketUri,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.networkScheduler = Objects.requireNonNull(
                networkScheduler,
                "networkScheduler"
        );
        this.socketUri = requireWebSocketUri(socketUri);
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
        stableConnectionNanos = TimeUnit.MILLISECONDS.toNanos(
                reconnectPolicy.stableConnectionDuration().toMillis()
        );
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        ClientState running;
        while (true) {
            ClientState state = currentAttempt.get();
            if (state.phase == Phase.TERMINAL) {
                throw new IllegalStateException(
                        "JavaOkHttpTextWebSocketClient is closed"
                );
            }
            if (state.phase == Phase.RUNNING) {
                return;
            }
            running = ClientState.running(listener, null, 0);
            if (currentAttempt.compareAndSet(state, running)) {
                break;
            }
        }
        if (!execute(this::connect)) {
            currentAttempt.compareAndSet(running, NEW);
            throw new IllegalStateException(
                    "Unable to start Java WebSocket client"
            );
        }
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        ClientState state = currentAttempt.get();
        ConnectionAttempt attempt = state.attempt;
        if (state.phase != Phase.RUNNING
                || attempt == null
                || attempt.openedAtNanos.get() == NOT_OPEN) {
            return false;
        }
        WebSocket socket = attempt.socket.get();
        try {
            return socket != null && socket.send(message);
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public void closeCurrent(CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        ClientState state = currentAttempt.get();
        ConnectionAttempt attempt = state.attempt;
        if (state.phase != Phase.RUNNING || attempt == null) {
            return;
        }
        closeSocket(
                attempt.socket.get(),
                closeCode(reason),
                closeMessage(reason)
        );
        finishAttempt(attempt);
    }

    @Override
    public void close() {
        ConnectionAttempt attempt;
        while (true) {
            ClientState state = currentAttempt.get();
            if (state.phase == Phase.TERMINAL) {
                return;
            }
            attempt = state.attempt;
            if (currentAttempt.compareAndSet(state, TERMINAL)) {
                break;
            }
        }
        closeAndCancel(attempt == null ? null : attempt.socket.get());
    }

    private void connect() {
        ConnectionAttempt attempt = new ConnectionAttempt();
        while (true) {
            ClientState state = currentAttempt.get();
            if (state.phase != Phase.RUNNING || state.attempt != null) {
                return;
            }
            if (currentAttempt.compareAndSet(
                    state,
                    state.withAttempt(attempt)
            )) {
                break;
            }
        }

        try {
            WebSocket socket = Objects.requireNonNull(
                    connector.connect(
                            socketUri,
                            new ConnectionListener(attempt)
                    ),
                    "connector returned null"
            );
            attempt.socket.compareAndSet(null, socket);
            if (!isCurrent(attempt)) {
                socket.cancel();
            }
        } catch (RuntimeException error) {
            finishAttempt(attempt);
        }
    }

    private void opened(
            ConnectionAttempt attempt,
            WebSocket socket
    ) {
        attempt.socket.compareAndSet(null, socket);
        if (!isCurrent(attempt)) {
            socket.cancel();
            return;
        }
        attempt.openedAtNanos.compareAndSet(
                NOT_OPEN,
                System.nanoTime()
        );
        emit(attempt, Listener::onOpen);
    }

    private void message(ConnectionAttempt attempt, String text) {
        emit(attempt, listener -> listener.onMessage(text));
    }

    private void binary(ConnectionAttempt attempt) {
        if (!isCurrentOpen(attempt)) {
            return;
        }
        closeSocket(
                attempt.socket.get(),
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
        if (!isCurrent(attempt)) {
            return;
        }
        if (!closeSocket(socket, code, reason)) {
            finishAttempt(attempt);
        }
    }

    private void finishAttempt(ConnectionAttempt attempt) {
        while (true) {
            ClientState state = currentAttempt.get();
            if (state.phase != Phase.RUNNING
                    || state.attempt != attempt) {
                return;
            }
            int unstableAttempts = isStable(attempt)
                    ? 1
                    : state.unstableAttempts + 1;
            boolean exhausted = unstableAttempts
                    >= reconnectPolicy.maxUnstableAttempts();
            ClientState next = exhausted
                    ? TERMINAL
                    : ClientState.running(
                            state.listener,
                            null,
                            unstableAttempts
                    );
            if (!currentAttempt.compareAndSet(state, next)) {
                continue;
            }
            if (exhausted) {
                state.listener.onEndpointTerminated();
            } else {
                schedule(
                        this::connect,
                        reconnectPolicy.reconnectInterval().toMillis()
                );
            }
            return;
        }
    }

    private void emit(
            ConnectionAttempt attempt,
            ListenerCallback callback
    ) {
        ClientState state = currentAttempt.get();
        if (state.phase != Phase.RUNNING
                || state.attempt != attempt
                || attempt.openedAtNanos.get() == NOT_OPEN) {
            return;
        }
        callback.invoke(state.listener);
    }

    private boolean isCurrent(ConnectionAttempt attempt) {
        ClientState state = currentAttempt.get();
        return state.phase == Phase.RUNNING
                && state.attempt == attempt;
    }

    private boolean isCurrentOpen(ConnectionAttempt attempt) {
        return isCurrent(attempt)
                && attempt.openedAtNanos.get() != NOT_OPEN;
    }

    private boolean isStable(ConnectionAttempt attempt) {
        long openedAt = attempt.openedAtNanos.get();
        return openedAt != NOT_OPEN
                && System.nanoTime() - openedAt
                        >= stableConnectionNanos;
    }

    private boolean execute(Runnable action) {
        try {
            networkScheduler.execute(action);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void schedule(Runnable action, long delayMillis) {
        try {
            networkScheduler.schedule(
                    action,
                    delayMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // Host shutdown is terminal for borrowed clients.
        }
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

    private static final class ClientState {

        private final Phase phase;
        private final Listener listener;
        private final ConnectionAttempt attempt;
        private final int unstableAttempts;

        private ClientState(
                Phase phase,
                Listener listener,
                ConnectionAttempt attempt,
                int unstableAttempts
        ) {
            this.phase = phase;
            this.listener = listener;
            this.attempt = attempt;
            this.unstableAttempts = unstableAttempts;
        }

        private static ClientState running(
                Listener listener,
                ConnectionAttempt attempt,
                int unstableAttempts
        ) {
            return new ClientState(
                    Phase.RUNNING,
                    listener,
                    attempt,
                    unstableAttempts
            );
        }

        private ClientState withAttempt(ConnectionAttempt value) {
            return running(listener, value, unstableAttempts);
        }
    }

    private static final class ConnectionAttempt {

        private final AtomicReference<WebSocket> socket =
                new AtomicReference<>();
        private final AtomicLong openedAtNanos =
                new AtomicLong(NOT_OPEN);
    }

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(URI uri, WebSocketListener listener);
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
        } catch (RuntimeException ignored) {
            // Cancellation below is the terminal fallback.
        } finally {
            socket.cancel();
        }
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
