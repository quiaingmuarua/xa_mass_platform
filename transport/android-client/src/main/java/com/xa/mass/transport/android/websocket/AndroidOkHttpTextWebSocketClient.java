package com.xa.mass.transport.android.websocket;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.xa.mass.transport.client.TextWebSocketClient;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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

    private final Object lifecycleLock = new Object();
    private final NetworkResources networkResources;
    private final URI socketUri;
    private final Duration requestTimeout;
    private final Duration reconnectInterval;

    private volatile boolean running;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile ConnectionAttempt activeAttempt;

    private Listener listener;
    private HandlerThread handlerThread;
    private Handler handler;
    private long nextGeneration;

    public AndroidOkHttpTextWebSocketClient(
            URI socketUri,
            Duration requestTimeout,
            Duration reconnectInterval
    ) {
        this(
                createNetworkResources(requestTimeout),
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
            if (closed) {
                throw new IllegalStateException(
                        "AndroidOkHttpTextWebSocketClient is closed"
                );
            }
            if (running) {
                return;
            }
            this.listener = listener;
            handlerThread = new HandlerThread(THREAD_NAME);
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
            running = true;
            target = handler;
        }
        target.post(this::connectOnHandlerThread);
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        ConnectionAttempt attempt = activeAttempt;
        if (!running
                || closed
                || !connected
                || attempt == null
                || attempt.socket == null) {
            return false;
        }

        boolean accepted;
        Throwable failure = null;
        try {
            accepted = attempt.socket.send(message);
        } catch (RuntimeException error) {
            accepted = false;
            failure = error;
        }
        if (!accepted) {
            attempt.socket.cancel();
            postDisconnect(attempt.generation, failure);
        }
        return accepted;
    }

    @Override
    public void closeCurrent(int code, String reason) {
        Handler target = currentHandler();
        ConnectionAttempt attempt = activeAttempt;
        if (target == null || attempt == null) {
            return;
        }
        target.post(() -> closeCurrentOnHandlerThread(
                attempt.generation,
                code,
                reason
        ));
    }

    @Override
    public boolean isConnected() {
        return running && !closed && connected;
    }

    @Override
    public void close() {
        HandlerThread thread;
        Handler callbackHandler;
        ConnectionAttempt attempt;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            connected = false;
            attempt = activeAttempt;
            activeAttempt = null;
            callbackHandler = handler;
            handler = null;
            thread = handlerThread;
            handlerThread = null;
            listener = null;
        }

        if (callbackHandler != null) {
            callbackHandler.removeCallbacksAndMessages(null);
        }
        if (attempt != null && attempt.socket != null) {
            try {
                attempt.socket.close(1000, "Client closed");
            } finally {
                attempt.socket.cancel();
            }
        }
        if (thread != null) {
            thread.quitSafely();
            if (Looper.myLooper() != thread.getLooper()) {
                try {
                    thread.join(requestTimeout.toMillis());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        networkResources.close.run();
    }

    private void connectOnHandlerThread() {
        if (!running || closed || activeAttempt != null) {
            return;
        }

        ConnectionAttempt attempt =
                new ConnectionAttempt(++nextGeneration);
        activeAttempt = attempt;
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
        activeAttempt = null;
        connected = false;

        Listener callback = listener;
        if (running && !closed && callback != null) {
            if (failure != null) {
                callback.onFailure(failure);
            }
            callback.onDisconnected();
        }
        scheduleReconnectOnHandlerThread();
    }

    private void scheduleReconnectOnHandlerThread() {
        Handler target = currentHandler();
        if (target == null || !running || closed) {
            return;
        }
        target.removeCallbacks(reconnectTask);
        target.postDelayed(
                reconnectTask,
                reconnectInterval.toMillis()
        );
    }

    private final Runnable reconnectTask = () -> {
        if (running && !closed && activeAttempt == null) {
            connectOnHandlerThread();
        }
    };

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
                if (isCurrent(generation) && running && !closed) {
                    callback.run();
                }
            });
        }
    }

    private Handler currentHandler() {
        synchronized (lifecycleLock) {
            return closed ? null : handler;
        }
    }

    private boolean isCurrent(long generation) {
        ConnectionAttempt attempt = activeAttempt;
        return attempt != null && attempt.generation == generation;
    }

    private ConnectionAttempt requireCurrent(long generation) {
        ConnectionAttempt attempt = activeAttempt;
        if (attempt == null || attempt.generation != generation) {
            return null;
        }
        return attempt;
    }

    private final class ConnectionListener extends WebSocketListener {

        private final long generation;

        private ConnectionListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Handler target = currentHandler();
            if (target == null) {
                webSocket.cancel();
                return;
            }
            target.post(() -> {
                if (!isCurrent(generation) || !running || closed) {
                    webSocket.cancel();
                    return;
                }
                ConnectionAttempt attempt = requireCurrent(generation);
                attempt.socket = webSocket;
                connected = true;
                Listener callback = listener;
                if (callback != null) {
                    callback.onOpen();
                }
            });
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            postConnectionCallback(generation, () -> {
                if (!connected) {
                    return;
                }
                Listener callback = listener;
                if (callback != null) {
                    callback.onText(text);
                }
            });
        }

        @Override
        public void onMessage(
                WebSocket webSocket,
                ByteString bytes
        ) {
            postConnectionCallback(generation, () -> {
                if (!connected) {
                    return;
                }
                Listener callback = listener;
                if (callback != null) {
                    callback.onBinary();
                }
            });
        }

        @Override
        public void onClosing(
                WebSocket webSocket,
                int code,
                String reason
        ) {
            webSocket.close(code, reason);
            postDisconnect(generation, null);
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
        private volatile WebSocket socket;

        private ConnectionAttempt(long generation) {
            this.generation = generation;
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
            Duration timeout
    ) {
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
