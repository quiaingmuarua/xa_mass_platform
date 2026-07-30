package com.xa.mass.worker.transport.socket.client;

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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class JdkLineSocketClient implements LineSocketClient {

    private final Object lock = new Object();
    private final SocketConnector connector;
    private final URI socketUri;
    private final Duration connectTimeout;
    private final Duration reconnectInterval;
    private final ExecutorService connectionExecutor;

    private Listener listener;
    private Connection current;
    private boolean running;
    private boolean closed;

    public JdkLineSocketClient(
            URI socketUri,
            Duration connectTimeout,
            Duration reconnectInterval
    ) {
        this(
                JdkLineSocketClient::connectSocket,
                socketUri,
                connectTimeout,
                reconnectInterval
        );
    }

    JdkLineSocketClient(
            SocketConnector connector,
            URI socketUri,
            Duration connectTimeout,
            Duration reconnectInterval
    ) {
        this.connector = Objects.requireNonNull(
                connector,
                "connector"
        );
        this.socketUri = requireSocketUri(socketUri);
        this.connectTimeout = requirePositive(
                connectTimeout,
                "connectTimeout"
        );
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        connectionExecutor = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "worker-line-socket"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "JdkLineSocketClient is closed"
                );
            }
            if (running) {
                return;
            }
            this.listener = listener;
            running = true;
        }
        connectionExecutor.execute(this::runConnections);
    }

    @Override
    public boolean sendLine(String message) {
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
    public boolean isConnected() {
        synchronized (lock) {
            return running && !closed && current != null;
        }
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
            connection = current;
            current = null;
        }
        if (connection != null) {
            closeQuietly(connection.socket);
        }
        connectionExecutor.shutdownNow();
        try {
            connectionExecutor.awaitTermination(
                    connectTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void runConnections() {
        while (isRunning()) {
            Connection connection = null;
            boolean opened = false;
            try {
                Socket socket = connector.connect(
                        socketUri,
                        connectTimeout
                );
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
                if (!install(connection)) {
                    closeQuietly(socket);
                    break;
                }
                opened = true;
                listener.onOpen();
                String line;
                while (isCurrent(connection)
                        && (line = reader.readLine()) != null) {
                    listener.onLine(line);
                }
            } catch (IOException | RuntimeException error) {
                if (isRunning()) {
                    listener.onFailure(error);
                }
            } finally {
                if (connection != null) {
                    clear(connection);
                    closeQuietly(connection.socket);
                }
                if (opened && isRunning()) {
                    listener.onDisconnected();
                }
            }
            if (isRunning() && !sleepBeforeReconnect()) {
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

    private boolean sleepBeforeReconnect() {
        try {
            Thread.sleep(reconnectInterval.toMillis());
            return true;
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
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
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
