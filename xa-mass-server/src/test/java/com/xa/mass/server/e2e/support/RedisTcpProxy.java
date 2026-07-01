package com.xa.mass.server.e2e.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RedisTcpProxy implements AutoCloseable {

    private final String upstreamHost;
    private final int upstreamPort;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger acceptedConnections = new AtomicInteger();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;

    private RedisTcpProxy(String upstreamHost, int upstreamPort, ServerSocket serverSocket) {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.serverSocket = serverSocket;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "redis-server-e2e-proxy-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::acceptLoop);
    }

    public static RedisTcpProxy start(String upstreamHost, int upstreamPort, int listenPort) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", listenPort));
        return new RedisTcpProxy(upstreamHost, upstreamPort, server);
    }

    public static RedisTcpProxy startUnchecked(String upstreamHost, int upstreamPort, int listenPort) {
        try {
            return start(upstreamHost, upstreamPort, listenPort);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Redis test proxy", exception);
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int acceptedConnections() {
        return acceptedConnections.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket downstream = serverSocket.accept();
                Socket upstream = new Socket();
                upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 1_000);
                sockets.add(downstream);
                sockets.add(upstream);
                acceptedConnections.incrementAndGet();
                executor.execute(() -> pipe(downstream, upstream));
                executor.execute(() -> pipe(upstream, downstream));
            } catch (SocketException exception) {
                if (running.get()) {
                    throw new IllegalStateException("Redis test proxy socket failed", exception);
                }
                return;
            } catch (IOException exception) {
                if (running.get()) {
                    throw new IllegalStateException("Redis test proxy accept failed", exception);
                }
                return;
            }
        }
    }

    private void pipe(Socket source, Socket target) {
        try (InputStream input = source.getInputStream(); OutputStream output = target.getOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
            // Tests close both sides deliberately to simulate Redis network loss.
        } finally {
            closeSocket(source);
            closeSocket(target);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeSocket(serverSocket);
        for (Socket socket : sockets) {
            closeSocket(socket);
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static void closeSocket(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
