package com.xa.mass.transport.android.websocket;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.xa.mass.transport.client.TextWebSocketClient;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
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
        implements TextWebSocketClient {

    private static final String THREAD_NAME =
            "xa-worker-websocket-client";

    private enum State {
        NEW,
        CONNECTING,
        CONNECTED,
        RECONNECT_SCHEDULED,
        CLOSED
    }

    private final Object lifecycleLock = new Object();
    private final NetworkResources networkResources;
    private final URI socketUri;
    private final Duration requestTimeout;
    private final Duration reconnectInterval;
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

    public AndroidOkHttpTextWebSocketClient(
            URI socketUri,
            Duration requestTimeout,
            Duration reconnectInterval
    ) {
        this(
                createNetworkResources(socketUri, requestTimeout),
                socketUri,
                requestTimeout,
                reconnectInterval
        );
    }

    AndroidOkHttpTextWebSocketClient(
            NetworkResources networkResources,
            URI socketUri,
            Duration requestTimeout,
            Duration reconnectInterval
    ) {
        this.networkResources = Objects.requireNonNull(
                networkResources,
                "networkResources"
        );
        this.socketUri = requireWebSocketUri(socketUri);
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
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

        boolean accepted;
        Throwable failure = null;
        try {
            accepted = active.socket.send(message);
        } catch (RuntimeException error) {
            accepted = false;
            failure = error;
        }
        if (!accepted) {
            active.socket.cancel();
            postDisconnect(active.generation, failure);
        }
        return accepted;
    }

    @Override
    public void closeCurrent(int code, String reason) {
        ActiveConnection active = activeConnection.get();
        Handler target = currentHandler();
        if (active == null || target == null) {
            return;
        }
        target.post(() -> closeCurrentOnHandlerThread(
                active.generation,
                code,
                reason
        ));
    }

    @Override
    public boolean isConnected() {
        return !closeRequested
                && visibleState == State.CONNECTED
                && activeConnection.get() != null;
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
        }

        if (target == null || thread == null) {
            visibleState = State.CLOSED;
            activeConnection.set(null);
            networkResources.close.run();
            return;
        }

        if (Looper.myLooper() == thread.getLooper()) {
            closeOnHandlerThread();
            thread.quitSafely();
        } else {
            CountDownLatch stopped = new CountDownLatch(1);
            boolean posted = target.post(() -> {
                try {
                    closeOnHandlerThread();
                } finally {
                    stopped.countDown();
                }
            });
            if (!posted || !await(stopped, requestTimeout)) {
                forceCloseOutsideHandlerThread();
            }
            thread.quitSafely();
            join(thread, requestTimeout);
        }

        synchronized (lifecycleLock) {
            handler = null;
            handlerThread = null;
        }
        networkResources.close.run();
    }

    private void startOnHandlerThread(Listener listener) {
        if (closeRequested) {
            return;
        }
        this.listener = listener;
        connectOnHandlerThread();
    }

    private void connectOnHandlerThread() {
        if (closeRequested
                || currentAttempt != null
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
            disconnectOnHandlerThread(attempt.generation, error);
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
            callback.onText(message);
        }
    }

    private void binaryOnHandlerThread(long generation) {
        if (!isConnectedGeneration(generation)) {
            return;
        }
        Listener callback = listener;
        if (callback != null) {
            callback.onBinary();
        }
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
        disconnectOnHandlerThread(generation, null);
    }

    private void disconnectOnHandlerThread(
            long generation,
            Throwable failure
    ) {
        ConnectionAttempt attempt = requireCurrent(generation);
        if (attempt == null) {
            return;
        }
        currentAttempt = null;
        ActiveConnection active = activeConnection.get();
        if (active != null && active.generation == generation) {
            activeConnection.compareAndSet(active, null);
        }

        Listener callback = listener;
        if (!closeRequested && callback != null) {
            if (failure != null) {
                callback.onFailure(failure);
            }
            callback.onDisconnected();
        }
        scheduleReconnectOnHandlerThread();
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
                reconnectInterval.toMillis()
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
        currentAttempt = null;
        activeConnection.set(null);
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
        visibleState = State.CLOSED;
    }

    private void postDisconnect(long generation, Throwable failure) {
        Handler target = currentHandler();
        if (target != null) {
            target.post(() -> disconnectOnHandlerThread(
                    generation,
                    failure
            ));
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
                postDisconnect(generation, null);
            }
        }

        @Override
        public void onClosed(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            postDisconnect(generation, null);
        }

        @Override
        public void onFailure(
                WebSocket webSocket,
                Throwable error,
                Response response
        ) {
            postDisconnect(generation, error);
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

    private static boolean await(
            CountDownLatch latch,
            Duration timeout
    ) {
        try {
            return latch.await(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(
            HandlerThread thread,
            Duration timeout
    ) {
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
