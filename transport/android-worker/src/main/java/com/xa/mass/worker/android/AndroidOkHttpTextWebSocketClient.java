package com.xa.mass.worker.android;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Android WebSocket connection whose state and callbacks are serialized on a
 * dedicated HandlerThread.
 */
public final class AndroidOkHttpTextWebSocketClient
        implements TextMessageClient {

    private static final String THREAD_NAME =
            "xa-worker-websocket-client";
    private static final int NORMAL_CLOSE = 1000;
    private static final int UNSUPPORTED_DATA = 1003;
    private static final int INVALID_DATA = 1007;
    private static final int INTERNAL_FAILURE = 1011;

    private enum State {
        NEW,
        CONNECTING,
        CONNECTED,
        RECONNECT_SCHEDULED,
        TERMINATED,
        CLOSED
    }

    private final Object lifecycleLock = new Object();
    private final NetworkResources networkResources;
    private final URI socketUri;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final AtomicReference<ActiveConnection> activeConnection =
            new AtomicReference<>();

    private volatile boolean closeRequested;
    private volatile State visibleState = State.NEW;
    private HandlerThread handlerThread;
    private Handler handler;
    private boolean started;

    // HandlerThread-owned state.
    private Listener listener;
    private ConnectionAttempt currentAttempt;
    private long nextGeneration;
    private int unstableAttempts;
    private Runnable stableResetTask;

    public AndroidOkHttpTextWebSocketClient(
            URI socketUri,
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this(
                createNetworkResources(socketUri, requestTimeout),
                socketUri,
                requestTimeout,
                reconnectPolicy
        );
    }

    AndroidOkHttpTextWebSocketClient(
            NetworkResources networkResources,
            URI socketUri,
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this.networkResources = Objects.requireNonNull(
                networkResources,
                "networkResources"
        );
        this.socketUri = requireWebSocketUri(socketUri);
        requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        Handler target;
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
            handlerThread = new HandlerThread(THREAD_NAME);
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
            target = handler;
        }
        if (!target.post(() -> startOnHandlerThread(listener))) {
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
        Handler target = currentHandler();
        if (active == null || target == null) {
            return;
        }
        target.post(() -> closeCurrentOnHandlerThread(
                active.generation,
                closeCode(reason),
                closeMessage(reason)
        ));
    }

    @Override
    public void close() {
        Handler target;
        HandlerThread thread;
        synchronized (lifecycleLock) {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            target = handler;
            thread = handlerThread;
            handler = null;
            handlerThread = null;
        }

        visibleState = State.CLOSED;
        ActiveConnection active = activeConnection.getAndSet(null);
        if (active != null) {
            active.socket.cancel();
        }
        networkResources.close.run();

        if (target == null || thread == null) {
            return;
        }

        target.removeCallbacksAndMessages(null);
        if (Looper.myLooper() == thread.getLooper()) {
            closeOnHandlerThread();
            thread.quitSafely();
        } else {
            boolean posted = target.post(() -> {
                closeOnHandlerThread();
                thread.quitSafely();
            });
            if (!posted) {
                forceCloseOutsideHandlerThread();
                thread.quitSafely();
            }
        }
    }

    private void startOnHandlerThread(Listener listener) {
        if (closeRequested) {
            return;
        }
        this.listener = listener;
        unstableAttempts = 0;
        connectOnHandlerThread();
    }

    private void connectOnHandlerThread() {
        if (closeRequested
                || currentAttempt != null
                || visibleState == State.TERMINATED
                || visibleState == State.CLOSED) {
            return;
        }
        handler.removeCallbacks(reconnectTask);
        ConnectionAttempt attempt =
                new ConnectionAttempt(++nextGeneration);
        currentAttempt = attempt;
        transitionTo(State.CONNECTING);
        try {
            WebSocket socket = networkResources.connector.connect(
                    socketUri,
                    new ConnectionListener(attempt.generation)
            );
            if (isCurrent(attempt.generation)) {
                attempt.socket = socket;
            } else {
                socket.cancel();
            }
        } catch (RuntimeException error) {
            disconnectOnHandlerThread(attempt.generation);
        }
    }

    private void openOnHandlerThread(
            long generation,
            WebSocket socket
    ) {
        ConnectionAttempt attempt = requireCurrent(generation);
        if (attempt == null || closeRequested) {
            socket.cancel();
            return;
        }
        attempt.socket = socket;
        activeConnection.set(
                new ActiveConnection(generation, socket)
        );
        transitionTo(State.CONNECTED);
        Listener callback = listener;
        if (callback != null) {
            callback.onOpen();
        }
        scheduleStableReset(generation);
    }

    private void textOnHandlerThread(
            long generation,
            String message
    ) {
        if (!isConnectedGeneration(generation)) {
            return;
        }
        Listener callback = listener;
        if (callback != null) {
            callback.onMessage(message);
        }
    }

    private void binaryOnHandlerThread(long generation) {
        if (!isConnectedGeneration(generation)) {
            return;
        }
        closeCurrentOnHandlerThread(
                generation,
                UNSUPPORTED_DATA,
                "Text messages only"
        );
    }

    private void closeCurrentOnHandlerThread(
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
        disconnectOnHandlerThread(generation);
    }

    private void disconnectOnHandlerThread(long generation) {
        ConnectionAttempt attempt = requireCurrent(generation);
        if (attempt == null) {
            return;
        }
        cancelStableReset();
        currentAttempt = null;
        ActiveConnection active = activeConnection.get();
        if (active != null && active.generation == generation) {
            activeConnection.compareAndSet(active, null);
        }

        Listener callback = listener;
        unstableAttempts++;
        if (unstableAttempts
                >= reconnectPolicy.maxUnstableAttempts()) {
            transitionTo(State.TERMINATED);
            if (!closeRequested && callback != null) {
                callback.onEndpointTerminated();
            }
        } else {
            scheduleReconnectOnHandlerThread();
        }
    }

    private void scheduleStableReset(long generation) {
        Handler target = currentHandler();
        if (target == null) {
            return;
        }
        cancelStableReset();
        stableResetTask = () -> {
            if (isConnectedGeneration(generation)) {
                unstableAttempts = 0;
            }
            stableResetTask = null;
        };
        target.postDelayed(
                stableResetTask,
                reconnectPolicy.stableConnectionDuration().toMillis()
        );
    }

    private void cancelStableReset() {
        Handler target = currentHandler();
        if (target != null && stableResetTask != null) {
            target.removeCallbacks(stableResetTask);
        }
        stableResetTask = null;
    }

    private void scheduleReconnectOnHandlerThread() {
        Handler target = currentHandler();
        if (target == null || closeRequested) {
            return;
        }
        transitionTo(State.RECONNECT_SCHEDULED);
        target.removeCallbacks(reconnectTask);
        target.postDelayed(
                reconnectTask,
                reconnectPolicy.reconnectInterval().toMillis()
        );
    }

    private final Runnable reconnectTask = () -> {
        if (!closeRequested
                && currentAttempt == null
                && visibleState == State.RECONNECT_SCHEDULED) {
            connectOnHandlerThread();
        }
    };

    private void closeOnHandlerThread() {
        Handler target = handler;
        if (target != null) {
            target.removeCallbacksAndMessages(null);
        }
        ConnectionAttempt attempt = currentAttempt;
        cancelStableReset();
        currentAttempt = null;
        activeConnection.set(null);
        stableResetTask = null;
        listener = null;
        transitionTo(State.CLOSED);
        if (attempt != null && attempt.socket != null) {
            try {
                attempt.socket.close(1000, "Client closed");
            } finally {
                attempt.socket.cancel();
            }
        }
    }

    private void forceCloseOutsideHandlerThread() {
        ActiveConnection active = activeConnection.getAndSet(null);
        if (active != null) {
            active.socket.cancel();
        }
        currentAttempt = null;
        listener = null;
        visibleState = State.CLOSED;
    }

    private void postDisconnect(long generation) {
        Handler target = currentHandler();
        if (target != null) {
            target.post(() -> disconnectOnHandlerThread(generation));
        }
    }

    private void postConnectionCallback(
            long generation,
            Runnable callback
    ) {
        Handler target = currentHandler();
        if (target != null) {
            target.post(() -> {
                if (isCurrent(generation) && !closeRequested) {
                    callback.run();
                }
            });
        }
    }

    private Handler currentHandler() {
        synchronized (lifecycleLock) {
            return closeRequested ? null : handler;
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
        return isCurrent(generation)
                && visibleState == State.CONNECTED;
    }

    private void transitionTo(State state) {
        visibleState = state;
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
                    () -> openOnHandlerThread(generation, webSocket)
            );
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            postConnectionCallback(
                    generation,
                    () -> textOnHandlerThread(generation, text)
            );
        }

        @Override
        public void onMessage(
                WebSocket webSocket,
                ByteString bytes
        ) {
            postConnectionCallback(
                    generation,
                    () -> binaryOnHandlerThread(generation)
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
                postDisconnect(generation);
            }
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            postDisconnect(generation);
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            postDisconnect(generation);
        }
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

    @FunctionalInterface
    interface WebSocketConnector {

        WebSocket connect(
                URI uri,
                WebSocketListener listener
        );
    }

    static final class NetworkResources {

        private final WebSocketConnector connector;
        private final Runnable close;

        NetworkResources(
                WebSocketConnector connector,
                Runnable close
        ) {
            this.connector = Objects.requireNonNull(
                    connector,
                    "connector"
            );
            this.close = Objects.requireNonNull(close, "close");
        }
    }

    private static NetworkResources createNetworkResources(
            URI socketUri,
            Duration timeout
    ) {
        requireWebSocketUri(socketUri);
        long timeoutMillis = requirePositive(
                timeout,
                "requestTimeout"
        ).toMillis();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        return new NetworkResources(
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
