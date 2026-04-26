package com.xa.mass.transport.socket.server;

import com.google.gson.JsonObject;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Line-delimited JSON socket transport server.
 */
public final class SocketTransportServer implements TransportServer {

    private static final Logger logger = LoggerFactory.getLogger(SocketTransportServer.class);
    public static final String BOUND_PORT_PROPERTY = "mass.socket.bound-port";

    private final String bindHost;
    private final int port;
    private final int maxConnections;
    private final SocketSessionManager sessionManager;
    private final SocketTransportFrameCodec frameCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile ExecutorService clientExecutor;

    public SocketTransportServer(String bindHost,
                                 int port,
                                 int maxConnections,
                                 SocketSessionManager sessionManager,
                                 SocketTransportFrameCodec frameCodec,
                                 TaskResultIngestChannel taskResultIngestChannel,
                                 WorkerSystemEventChannel systemEventChannel) {
        this.bindHost = bindHost;
        this.port = port;
        this.maxConnections = maxConnections;
        this.sessionManager = sessionManager;
        this.frameCodec = frameCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
    }

    @Override
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ServerSocket created = new ServerSocket(port, 50, InetAddress.getByName(bindHost));
        this.serverSocket = created;
        this.clientExecutor = Executors.newCachedThreadPool();
        this.acceptThread = new Thread(this::acceptLoop, "mass-socket-accept-" + created.getLocalPort());
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        System.setProperty(BOUND_PORT_PROPERTY, String.valueOf(created.getLocalPort()));
        logger.info("Socket server started on {}:{}", bindHost, created.getLocalPort());
    }

    @Override
    public void stop() throws Exception {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ServerSocket existingServer = this.serverSocket;
        if (existingServer != null && !existingServer.isClosed()) {
            existingServer.close();
        }
        Thread existingThread = this.acceptThread;
        if (existingThread != null) {
            existingThread.interrupt();
            existingThread.join(TimeUnit.SECONDS.toMillis(5));
        }
        ExecutorService executor = this.clientExecutor;
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        sessionManager.shutdown();
        System.clearProperty(BOUND_PORT_PROPERTY);
        logger.info("Socket server stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                if (sessionManager.getActiveConnectionCount() >= maxConnections) {
                    logger.warn("Rejecting socket client because maxConnections={} has been reached", maxConnections);
                    client.close();
                    continue;
                }
                clientExecutor.submit(() -> handleClient(client));
            } catch (IOException ex) {
                if (running.get()) {
                    logger.error("Socket accept loop failed", ex);
                }
                return;
            }
        }
    }

    private void handleClient(Socket client) {
        String endpointId = UUID.randomUUID().toString();
        String boundWorkerId = null;
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                JsonObject frame = frameCodec.parseObject(line);
                if (frame == null) {
                    continue;
                }
                if (frameCodec.isHelloFrame(frame)) {
                    boundWorkerId = frameCodec.extractWorkerId(frame);
                    if (boundWorkerId == null) {
                        continue;
                    }
                    sessionManager.addSession(boundWorkerId, endpointId, socket, writer);
                    continue;
                }
                if (boundWorkerId == null) {
                    logger.warn("Ignoring socket frame before hello handshake: endpointId={}", endpointId);
                    continue;
                }
                if (frameCodec.isHeartbeatFrame(frame)) {
                    if (systemEventChannel != null) {
                        systemEventChannel.publishWorkerHeartbeat(
                                boundWorkerId,
                                "socket heartbeat",
                                frameCodec.extractTraceId(frame)
                        );
                    }
                    continue;
                }
                if (frameCodec.isCanonicalTaskResult(frame)) {
                    if (taskResultIngestChannel == null) {
                        logger.warn("Canonical socket task result ignored because ingest channel is unavailable");
                        continue;
                    }
                    TaskResultReport report = frameCodec.decodeCanonicalTaskResult(frame);
                    taskResultIngestChannel.ingest(report);
                    continue;
                }
                logger.warn("Ignoring unsupported socket frame: endpointId={}, workerId={}", endpointId, boundWorkerId);
            }
        } catch (Exception ex) {
            if (running.get()) {
                logger.error("Socket client loop failed: endpointId={}, workerId={}", endpointId, boundWorkerId, ex);
            }
        } finally {
            sessionManager.removeSession(endpointId);
        }
    }
}
