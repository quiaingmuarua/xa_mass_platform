package com.xa.mass.worker.transport.socket;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
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
import java.util.Optional;

public final class SocketWorkerTransport implements AutoCloseable {

    private final SocketConnector connector;
    private final URI socketUri;
    private final String workerId;
    private final Duration connectTimeout;
    private final Duration reconnectInterval;
    private final WorkerDeliveryCodec codec;
    private final WorkerCommandProcessor processor;
    private volatile boolean running;
    private volatile Socket socket;
    private volatile WorkerResult pendingResult;

    public SocketWorkerTransport(
            URI socketUri,
            String workerId,
            Duration connectTimeout,
            Duration reconnectInterval,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this(
                SocketWorkerTransport::connectSocket,
                socketUri,
                workerId,
                connectTimeout,
                reconnectInterval,
                codec,
                processor
        );
    }

    SocketWorkerTransport(
            SocketConnector connector,
            URI socketUri,
            String workerId,
            Duration connectTimeout,
            Duration reconnectInterval,
            WorkerDeliveryCodec codec,
            WorkerCommandProcessor processor
    ) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.socketUri = requireSocketUri(socketUri);
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        this.workerId = workerId;
        this.connectTimeout = requirePositive(
                connectTimeout,
                "connectTimeout"
        );
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public void runForever() throws InterruptedException {
        if (running) {
            return;
        }
        running = true;
        try {
            while (running) {
                try {
                    runConnection();
                } catch (IOException | WorkerException ignored) {
                    closeCurrentSocket();
                }
                if (running) {
                    Thread.sleep(reconnectInterval.toMillis());
                }
            }
        } finally {
            running = false;
            closeCurrentSocket();
        }
    }

    @Override
    public void close() {
        running = false;
        closeCurrentSocket();
    }

    public boolean isConnected() {
        Socket current = socket;
        return running
                && current != null
                && current.isConnected()
                && !current.isClosed();
    }

    public boolean hasPendingResult() {
        return pendingResult != null;
    }

    public URI socketUri() {
        return socketUri;
    }

    private void runConnection() throws IOException {
        Socket connected = connector.connect(socketUri, connectTimeout);
        socket = connected;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        connected.getInputStream(),
                        StandardCharsets.UTF_8
                )
        ); BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        connected.getOutputStream(),
                        StandardCharsets.UTF_8
                )
        )) {
            writeLine(
                    writer,
                    codec.encodeWorkerConnectionBind(
                            new WorkerConnectionBind(workerId)
                    )
            );
            sendPending(writer);
            String line;
            while (running && (line = reader.readLine()) != null) {
                WorkerCommand command = codec.decodeWorkerCommand(line);
                if (command == null) {
                    throw new WorkerException(
                            com.xa.mass.worker.error.WorkerErrorCode
                                    .COMMAND_MESSAGE_INVALID,
                            "connectionMessage.decode",
                            null,
                            null
                    );
                }
                Optional<WorkerResult> result = processor.process(command);
                if (!result.isPresent()) {
                    continue;
                }
                pendingResult = result.get();
                sendPending(writer);
            }
        } finally {
            if (socket == connected) {
                socket = null;
            }
        }
    }

    private void sendPending(BufferedWriter writer) throws IOException {
        WorkerResult sending = pendingResult;
        if (sending == null) {
            return;
        }
        writeLine(
                writer,
                codec.encodeWorkerResult(sending)
        );
        if (pendingResult == sending) {
            pendingResult = null;
        }
    }

    private static void writeLine(
            BufferedWriter writer,
            String value
    ) throws IOException {
        writer.write(value);
        writer.write('\n');
        writer.flush();
    }

    private synchronized void closeCurrentSocket() {
        Socket current = socket;
        socket = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException ignored) {
            // Socket teardown is best effort.
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

    @FunctionalInterface
    interface SocketConnector {

        Socket connect(URI socketUri, Duration timeout) throws IOException;
    }
}
