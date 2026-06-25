package com.xa.mass.transport.socket.server;

import com.google.gson.JsonObject;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.AdapterHostExecutor;
import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import com.xa.mass.transport.runtime.embedded.AdapterInboundResultProcessor;
import com.xa.mass.transport.runtime.embedded.JsonAdapterResultDiagnosticsProvider;
import com.xa.mass.transport.runtime.embedded.WorkerChannelActionReplyResultFrameReader;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Line-delimited JSON socket transport server.
 */
public final class SocketTransportServer implements TransportServer {

    private static final Logger logger = LoggerFactory.getLogger(SocketTransportServer.class);
    public static final String BOUND_PORT_PROPERTY = "mass.socket.bound-port";

    private final String adapterId;
    private final String bindHost;
    private final int port;
    private final int maxConnections;
    private final SocketSessionManager sessionManager;
    private final SocketTransportFrameCodec frameCodec;
    private final AdapterHostExecutor hostExecutor;
    private final WorkerChannelActionReplyResultFrameReader resultFrameReader;
    private final AdapterInboundResultProcessor<JsonObject> resultProcessor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Future<?>> clientTasks = ConcurrentHashMap.newKeySet();

    private volatile ServerSocket serverSocket;
    private volatile Future<?> acceptTask;

    public SocketTransportServer(String adapterId,
                                 String bindHost,
                                 int port,
                                 int maxConnections,
                                 SocketSessionManager sessionManager,
                                 SocketTransportFrameCodec frameCodec,
                                 AdapterResultIngressSink resultIngressSink,
                                 AdapterHostExecutor hostExecutor) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.bindHost = bindHost;
        this.port = port;
        this.maxConnections = maxConnections;
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.hostExecutor = Objects.requireNonNull(hostExecutor, "hostExecutor");
        TransportJsonFrameParser resultFrameParser = new TransportJsonFrameParser();
        this.resultFrameReader = new WorkerChannelActionReplyResultFrameReader(resultFrameParser);
        this.resultProcessor = AdapterInboundResultProcessor.with(
                this.resultFrameReader,
                resultIngressSink,
                new JsonAdapterResultDiagnosticsProvider(this.adapterId, resultFrameParser)::diagnostics
        );
    }

    @Override
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ServerSocket created = new ServerSocket(port, 50, InetAddress.getByName(bindHost));
        try {
            this.serverSocket = created;
            this.acceptTask = hostExecutor.submit(this::acceptLoop);
            System.setProperty(BOUND_PORT_PROPERTY, String.valueOf(created.getLocalPort()));
            logger.info("Socket server started on {}:{}", bindHost, created.getLocalPort());
        } catch (RuntimeException ex) {
            running.set(false);
            this.serverSocket = null;
            closeQuietly(created);
            throw ex;
        }
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
        Future<?> existingAcceptTask = this.acceptTask;
        if (existingAcceptTask != null) {
            existingAcceptTask.cancel(true);
        }
        for (Future<?> clientTask : clientTasks) {
            clientTask.cancel(true);
        }
        sessionManager.shutdown();
        waitForClientTasksToFinish();
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
                submitClient(client);
            } catch (IOException ex) {
                if (running.get()) {
                    logger.error("Socket accept loop failed", ex);
                }
                return;
            }
        }
    }

    private void submitClient(Socket client) {
        try {
            Future<?> clientTask = hostExecutor.submit(() -> handleClient(client));
            clientTasks.add(clientTask);
        } catch (RejectedExecutionException ex) {
            logger.warn("Rejecting socket client because runtime executor is unavailable", ex);
            closeQuietly(client);
        }
    }

    private void handleClient(Socket client) {
        String endpointId = UUID.randomUUID().toString();
        String boundWorkerId = null;
        String boundWorkerGroupId = null;
        String boundRouteKey = null;
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
                    boundWorkerGroupId = frameCodec.extractWorkerGroupId(frame);
                    boundRouteKey = frameCodec.extractRouteKey(frame);
                    if (boundWorkerId == null || boundWorkerGroupId == null || boundRouteKey == null) {
                        logger.warn("Ignoring socket hello without workerId/workerGroupId/routeKey: endpointId={}",
                                endpointId);
                        continue;
                    }
                    sessionManager.addSession(boundWorkerGroupId, boundRouteKey, boundWorkerId, endpointId, socket, writer);
                    continue;
                }
                if (boundWorkerId == null) {
                    logger.warn("Ignoring socket frame before hello handshake: endpointId={}", endpointId);
                    continue;
                }
                if (frameCodec.isHeartbeatFrame(frame)) {
                    String traceId = frameCodec.extractTraceId(frame);
                    sessionManager.recordHeartbeat(boundRouteKey, boundWorkerId, endpointId, "socket heartbeat", traceId);
                    continue;
                }
                if (resultFrameReader.isResultFrame(frame)) {
                    resultProcessor.processResult(frame);
                    continue;
                }
                logger.warn("Ignoring unsupported socket frame: endpointId={}, routeKey={}, workerId={}",
                        endpointId, boundRouteKey, boundWorkerId);
            }
        } catch (Exception ex) {
            if (running.get()) {
                logger.error("Socket client loop failed: endpointId={}, routeKey={}, workerId={}",
                        endpointId, boundRouteKey, boundWorkerId, ex);
            }
        } finally {
            sessionManager.removeSession(endpointId);
            clientTasks.removeIf(Future::isDone);
        }
    }

    private void waitForClientTasksToFinish() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!clientTasks.isEmpty() && System.nanoTime() < deadlineNanos) {
            clientTasks.removeIf(Future::isDone);
            if (!clientTasks.isEmpty()) {
                Thread.sleep(25L);
            }
        }
        clientTasks.removeIf(Future::isDone);
    }

    private void closeQuietly(Socket client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // Best-effort rejection cleanup.
        }
    }

    private void closeQuietly(ServerSocket server) {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // Best-effort startup cleanup.
        }
    }

}

