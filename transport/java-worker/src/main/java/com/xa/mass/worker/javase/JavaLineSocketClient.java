package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.TextMessageReconnectState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

final class JavaLineSocketClient implements TextMessageClient {

    private final Object lock = new Object();
    private final ExecutorService socketExecutor;
    private final SocketConnector connector;
    private final URI socketUri;
    private final Duration connectTimeout;
    private final TextMessageReconnectPolicy reconnectPolicy;
    private final TextMessageReconnectState reconnectState;

    private Listener listener;
    private Connection current;
    private boolean running;
    private boolean closed;

    JavaLineSocketClient(
            ExecutorService socketExecutor,
            URI socketUri,
            Duration connectTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this(
                socketExecutor,
                JavaLineSocketClient::connectSocket,
                socketUri,
                connectTimeout,
                reconnectPolicy
        );
    }

    JavaLineSocketClient(
            ExecutorService socketExecutor,
            SocketConnector connector,
            URI socketUri,
            Duration connectTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this.socketExecutor = Objects.requireNonNull(
                socketExecutor,
                "socketExecutor"
        );
        this.connector = Objects.requireNonNull(connector, "connector");
        this.socketUri = requireSocketUri(socketUri);
        this.connectTimeout = requirePositive(
                connectTimeout,
                "connectTimeout"
        );
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
                        "JavaLineSocketClient is closed"
                );
            }
            if (running) {
                return;
            }
            this.listener = listener;
            running = true;
        }
        socketExecutor.execute(this::runConnections);
    }

    @Override
    public boolean send(String message) {
        Objects.requireNonNull(message, "message");
        Connection connection;
        synchronized (lock) {
            connection = current;
            if (!running || closed || connection == null) {
                return false;
            }
        }
        try {
            synchronized (connection.writer) {
                connection.writer.write(message);
                connection.writer.write('\n');
                connection.writer.flush();
            }
            return true;
        } catch (IOException error) {
            closeQuietly(connection.socket);
            return false;
        }
    }

    @Override
    public void closeCurrent(CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        Connection connection;
        synchronized (lock) {
            connection = current;
            if (!running || closed || connection == null) {
                return;
            }
        }
        closeQuietly(connection.socket);
    }

    @Override
    public void close() {
        Connection connection;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            reconnectState.close();
            connection = current;
            current = null;
            listener = null;
        }
        if (connection != null) {
            closeQuietly(connection.socket);
        }
    }

    private void runConnections() {
        while (isRunning()) {
            long generation;
            try {
                generation = reconnectState.beginAttempt();
            } catch (IllegalStateException terminal) {
                break;
            }
            Socket socket = null;
            Connection connection = null;
            long openedAtNanos = 0L;
            try {
                socket = connector.connect(socketUri, connectTimeout);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                connection = new Connection(socket, reader, writer);
                if (!install(connection)
                        || !reconnectState.opened(generation)) {
                    closeQuietly(socket);
                    break;
                }
                openedAtNanos = System.nanoTime();
                currentListener().onOpen();
                String line;
                while (isCurrent(connection)
                        && (line = reader.readLine()) != null) {
                    currentListener().onMessage(line);
                }
            } catch (IOException | RuntimeException ignored) {
                // Transient connection failures stay inside the Client.
            } finally {
                if (connection != null) {
                    clear(connection);
                    closeQuietly(connection.socket);
                } else if (socket != null) {
                    closeQuietly(socket);
                }
            }

            if (openedAtNanos != 0L
                    && System.nanoTime() - openedAtNanos
                    >= reconnectPolicy
                    .stableConnectionDuration()
                    .toNanos()) {
                reconnectState.becameStable(generation);
            }
            TextMessageReconnectState.DisconnectAction action =
                    reconnectState.disconnected(generation);
            if (action
                    == TextMessageReconnectState.DisconnectAction.TERMINATE) {
                Listener callback = nullableListener();
                if (callback != null) {
                    callback.onEndpointTerminated();
                }
                break;
            }
            if (action
                    != TextMessageReconnectState.DisconnectAction.RECONNECT
                    || !sleepBeforeReconnect()) {
                break;
            }
        }
    }

    private boolean install(Connection connection) {
        synchronized (lock) {
            if (!running || closed) {
                return false;
            }
            current = connection;
            return true;
        }
    }

    private boolean isCurrent(Connection connection) {
        synchronized (lock) {
            return running && !closed && current == connection;
        }
    }

    private void clear(Connection connection) {
        synchronized (lock) {
            if (current == connection) {
                current = null;
            }
        }
    }

    private boolean isRunning() {
        synchronized (lock) {
            return running && !closed;
        }
    }

    private Listener currentListener() {
        Listener callback = nullableListener();
        if (callback == null) {
            throw new IllegalStateException("Text client listener is absent");
        }
        return callback;
    }

    private Listener nullableListener() {
        synchronized (lock) {
            return listener;
        }
    }

    private boolean sleepBeforeReconnect() {
        try {
            Thread.sleep(
                    reconnectPolicy.reconnectInterval().toMillis()
            );
            return isRunning();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    interface SocketConnector {

        Socket connect(URI socketUri, Duration timeout)
                throws IOException;
    }

    private static final class Connection {

        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private Connection(
                Socket socket,
                BufferedReader reader,
                BufferedWriter writer
        ) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }
    }

    private static Socket connectSocket(
            URI socketUri,
            Duration timeout
    ) throws IOException {
        Socket socket = new Socket();
        socket.connect(
                new InetSocketAddress(
                        socketUri.getHost(),
                        socketUri.getPort()
                ),
                Math.toIntExact(timeout.toMillis())
        );
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        return socket;
    }

    private static URI requireSocketUri(URI value) {
        if (value == null
                || !"tcp".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null
                || value.getPort() < 1
                || value.getPort() > 65_535
                || (value.getPath() != null
                && !value.getPath().isEmpty())) {
            throw new IllegalArgumentException(
                    "socketUri must be an absolute tcp://host:port URI"
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
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket teardown is best effort.
        }
    }
}
