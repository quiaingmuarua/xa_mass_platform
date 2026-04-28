package com.xa.mass.workerpack.sample.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientState;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientStateRegistry;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SampleWorkerSocketClient implements SampleWorkerClient {

    private static final Logger logger = LoggerFactory.getLogger(SampleWorkerSocketClient.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;

    private final String workerId;
    private final URI serverUri;
    private final String taskResultStatus;
    private final SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();
    private final SampleWorkerTaskFrameHandler taskFrameHandler =
            new SampleWorkerTaskFrameHandler("socket", "realtime", "sample-socket-client");
    private final ScheduledExecutorService reconnectScheduler;
    private final ScheduledExecutorService taskResponseScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;
    private volatile Thread readerThread;
    private volatile boolean intentionalClose = false;

    public SampleWorkerSocketClient(URI serverUri, String workerId) {
        this(serverUri, workerId, "SUCCESS");
    }

    public SampleWorkerSocketClient(URI serverUri, String workerId, String taskResultStatus) {
        if (serverUri == null) {
            throw new IllegalArgumentException("serverUri must not be null");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.serverUri = serverUri;
        this.workerId = workerId;
        this.taskResultStatus = normalizeConfiguredTaskResultStatus(taskResultStatus);
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socket-reconnect-scheduler-" + workerId);
            t.setDaemon(true);
            return t;
        });
        this.taskResponseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-socket-task-response-scheduler-" + workerId);
            t.setDaemon(true);
            return t;
        });
        SampleCommandRuntime.initialize();
    }

    public SampleWorkerSocketClient(String workerId) {
        this(URI.create("tcp://127.0.0.1:18089"), workerId, "SUCCESS");
    }

    @Override
    public String adapterId() {
        return "socket";
    }

    @Override
    public String getWorkerId() {
        return workerId;
    }

    @Override
    public synchronized void connect(URI ignoredServerUri) throws Exception {
        connectBlocking(10, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void disconnect() throws Exception {
        closeConnection();
    }

    @Override
    public boolean isConnected() {
        return isSocketOpen();
    }

    @Override
    public void sendMessage(String message) throws Exception {
        sendFrame(message);
    }

    @Override
    public synchronized boolean connectBlocking(long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (isSocketOpen()) {
            return true;
        }
        try {
            intentionalClose = false;
            Socket created = new Socket();
            int timeoutMs = (int) Math.max(1L, timeUnit.toMillis(timeout));
            created.connect(new InetSocketAddress(resolveHost(serverUri), serverUri.getPort()), timeoutMs);
            created.setTcpNoDelay(true);
            BufferedWriter createdWriter = new BufferedWriter(
                    new OutputStreamWriter(created.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader createdReader = new BufferedReader(
                    new InputStreamReader(created.getInputStream(), StandardCharsets.UTF_8));

            this.socket = created;
            this.writer = createdWriter;
            this.reader = createdReader;

            startReaderLoop(createdReader);
            sendFrame(buildHelloFrame());
            reconnectAttempts.set(0);
            logger.info("[{}] Connected to socket server {}:{}", workerId, resolveHost(serverUri), serverUri.getPort());
            return true;
        } catch (Exception ex) {
            closeResources(false);
            logger.warn("[{}] Failed to connect socket client: {}", workerId, ex.getMessage());
            return false;
        }
    }

    void handleInboundFrame(String rawFrame) {
        JsonObject frame = frameCodec.parseObject(rawFrame);
        if (frame == null) {
            logger.warn("[{}] Ignoring malformed socket frame", workerId);
            return;
        }
        if (taskFrameHandler.isTaskDispatchFrame(frame)) {
            handleTaskFrame(frame);
            return;
        }
        if (taskFrameHandler.isTaskResultFrame(frame)) {
            logger.debug("[{}] Ignoring inbound canonical socket task result frame {}", workerId, frame.get("messageId"));
            return;
        }
        logger.warn("[{}] Ignoring unsupported socket worker frame", workerId);
    }

    protected void sendFrame(String frameJson) throws IOException {
        BufferedWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new IOException("socket writer is not available");
        }
        synchronized (currentWriter) {
            currentWriter.write(frameJson);
            currentWriter.newLine();
            currentWriter.flush();
        }
    }

    protected boolean isSocketOpen() {
        Socket currentSocket = socket;
        return currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed();
    }

    protected synchronized void closeConnection() {
        logger.info("[{}] Intentionally closing socket connection...", workerId);
        intentionalClose = true;
        closeResources(true);
        shutdownSchedulers();
    }

    private void handleTaskFrame(JsonObject taskFrame) {
        SampleClientState state = getSampleClientState();
        SampleWorkerTaskFrameHandler.TaskResponsePlan plan = taskFrameHandler.prepareResponse(
                taskFrame,
                workerId,
                taskResultStatus,
                state
        );
        if (plan == null) {
            return;
        }
        sendTaskResponse(plan.responseJson(), plan.messageId(), plan.delayMillis(), plan.disconnectWorkerId());
    }

    private void sendTaskResponse(String responseJson, String messageId, long delayMillis, String disconnectWorkerId) {
        if (delayMillis <= 0L) {
            try {
                sendFrame(responseJson);
                logger.debug("[{}] Sent socket task response for messageId={}", workerId, messageId);
                disconnectAfterTaskResultIfRequested(disconnectWorkerId);
            } catch (Exception e) {
                logger.warn("[{}] Failed to send socket task response for messageId={}: {}", workerId, messageId, e.getMessage());
            }
            return;
        }

        logger.info("[{}] Scheduling socket task response for messageId={} after {} ms", workerId, messageId, delayMillis);
        taskResponseScheduler.schedule(() -> {
            if (!isSocketOpen()) {
                logger.warn("[{}] Skip delayed socket task response because client is disconnected. messageId={}", workerId, messageId);
                return;
            }
            try {
                sendFrame(responseJson);
                logger.debug("[{}] Sent delayed socket task response for messageId={}", workerId, messageId);
                disconnectAfterTaskResultIfRequested(disconnectWorkerId);
            } catch (Exception e) {
                logger.warn("[{}] Failed to send delayed socket task response for messageId={}: {}", workerId, messageId, e.getMessage());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void disconnectAfterTaskResultIfRequested(String disconnectWorkerId) {
        if (disconnectWorkerId == null || disconnectWorkerId.isBlank()) {
            return;
        }
        taskResponseScheduler.schedule(() -> closeTargetWorker(disconnectWorkerId), 100, TimeUnit.MILLISECONDS);
    }

    private void closeTargetWorker(String targetWorkerId) {
        if (workerId.equals(targetWorkerId)) {
            logger.info("[{}] Closing current socket worker after disconnect task result", workerId);
            closeConnection();
            return;
        }
        ClientSessionManager clientSessionManager = SampleCommandRuntime.getService(ClientSessionManager.class);
        if (clientSessionManager == null) {
            logger.warn("[{}] Cannot close target worker {} because ClientSessionManager is not registered",
                    workerId, targetWorkerId);
            return;
        }
        SampleWorkerClient targetClient = clientSessionManager.getClient(targetWorkerId);
        if (targetClient == null) {
            logger.warn("[{}] Cannot close target worker {} because client is missing", workerId, targetWorkerId);
            return;
        }
        logger.info("[{}] Closing target worker {} after disconnect task result", workerId, targetWorkerId);
        try {
            targetClient.disconnect();
        } catch (Exception e) {
            logger.warn("[{}] Failed to close target worker {}: {}", workerId, targetWorkerId, e.getMessage());
        }
    }

    private void startReaderLoop(BufferedReader currentReader) {
        Thread thread = new Thread(() -> {
            try {
                String line;
                while ((line = currentReader.readLine()) != null) {
                    handleInboundFrame(line);
                }
            } catch (IOException ex) {
                if (!intentionalClose) {
                    logger.warn("[{}] Socket read loop ended: {}", workerId, ex.getMessage());
                }
            } finally {
                boolean reconnect = !intentionalClose;
                closeResources(false);
                if (reconnect) {
                    scheduleReconnect();
                }
            }
        }, "mock-socket-reader-" + workerId);
        thread.setDaemon(true);
        thread.start();
        this.readerThread = thread;
    }

    private void scheduleReconnect() {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            logger.warn("[{}] Reached max socket reconnect attempts ({}). Giving up.", workerId, MAX_RECONNECT_ATTEMPTS);
            shutdownSchedulers();
            return;
        }
        long delay = (long) (INITIAL_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts.get()));
        delay = Math.min(delay, MAX_RECONNECT_DELAY_MS);
        logger.info("[{}] Will attempt socket reconnect in {} seconds. Attempt: {}",
                workerId,
                delay / 1000.0,
                reconnectAttempts.get() + 1);
        reconnectScheduler.schedule(() -> {
            logger.info("[{}] Attempting socket reconnect... (Attempt {})",
                    workerId,
                    reconnectAttempts.incrementAndGet());
            try {
                connectBlocking(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("[{}] Socket reconnect attempt interrupted: {}", workerId, e.getMessage());
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private SampleClientState getSampleClientState() {
        SampleClientStateRegistry stateRegistry = SampleCommandRuntime.getService(SampleClientStateRegistry.class);
        return stateRegistry == null ? null : stateRegistry.getOrCreate(workerId);
    }

    private String buildHelloFrame() {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty("workerId", workerId);
        return GSON.toJson(frame);
    }

    private synchronized void closeResources(boolean interruptReader) {
        BufferedReader currentReader = this.reader;
        BufferedWriter currentWriter = this.writer;
        Socket currentSocket = this.socket;
        Thread currentReaderThread = this.readerThread;
        this.reader = null;
        this.writer = null;
        this.socket = null;
        this.readerThread = null;

        closeQuietly(currentSocket);
        closeQuietly(currentWriter);
        if (interruptReader && currentReaderThread != null) {
            currentReaderThread.interrupt();
        }
    }

    private void shutdownSchedulers() {
        shutdownExecutor(taskResponseScheduler, "socket task response scheduler");
        shutdownExecutor(reconnectScheduler, "socket reconnect scheduler");
    }

    private void shutdownExecutor(ScheduledExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        List<Runnable> cancelledTasks = executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("[{}] {} did not terminate cleanly within timeout.", workerId, name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("[{}] {} shut down. Cancelled {} queued tasks.", workerId, name, cancelledTasks.size());
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort close only.
        }
    }

    private String resolveHost(URI uri) {
        String host = uri.getHost();
        if (host != null && !host.isBlank()) {
            return host;
        }
        String authority = uri.getAuthority();
        if (authority != null && !authority.isBlank()) {
            int idx = authority.lastIndexOf(':');
            return idx > 0 ? authority.substring(0, idx) : authority;
        }
        return "127.0.0.1";
    }

    private String normalizeConfiguredTaskResultStatus(String configured) {
        if (configured == null || configured.isBlank()) {
            return "SUCCESS";
        }
        String normalized = configured.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }
}

