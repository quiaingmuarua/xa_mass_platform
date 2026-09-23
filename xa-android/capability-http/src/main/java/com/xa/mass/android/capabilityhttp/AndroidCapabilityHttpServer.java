package com.xa.mass.android.capabilityhttp;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;

public final class AndroidCapabilityHttpServer implements AutoCloseable {

    public static final String LOOPBACK_HOST = "127.0.0.1";

    private static final int SOCKET_READ_TIMEOUT_MILLIS = 5_000;

    private final int configuredPort;
    private final CapabilityNanoHttpServer server;

    private AndroidCapabilityHttpServer(
            int port,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(
                    "port must be in 0..65535"
            );
        }
        configuredPort = port;
        server = new CapabilityNanoHttpServer(
                LOOPBACK_HOST,
                port,
                definitions
        );
    }

    public static AndroidCapabilityHttpServer create(
            int port,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        return new AndroidCapabilityHttpServer(port, definitions);
    }

    public synchronized void start() throws IOException {
        if (server.isAlive()) {
            return;
        }
        server.start(SOCKET_READ_TIMEOUT_MILLIS, false);
    }

    public boolean isRunning() {
        return server.isAlive();
    }

    public URI endpoint() {
        int listeningPort = server.getListeningPort();
        int resolvedPort = listeningPort > 0
                ? listeningPort
                : configuredPort;
        return URI.create(
                "http://" + LOOPBACK_HOST + ":" + resolvedPort
        );
    }

    @Override
    public synchronized void close() {
        server.stop();
    }
}
