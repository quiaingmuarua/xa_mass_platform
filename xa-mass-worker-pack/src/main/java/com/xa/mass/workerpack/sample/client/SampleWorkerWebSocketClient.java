package com.xa.mass.workerpack.sample.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientState;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientStateRegistry;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SampleWorkerWebSocketClient extends WebSocketClient implements SampleWorkerClient {
    private static final Logger logger = LoggerFactory.getLogger(SampleWorkerWebSocketClient.class);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;

    private final Gson gson = new Gson();
    private final ScheduledExecutorService reconnectScheduler;
    private final ScheduledExecutorService taskResponseScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final String workerId;
    private final SampleWorkerTaskFrameHandler taskFrameHandler = new SampleWorkerTaskFrameHandler();
    private final String taskResultStatus;

    private boolean intentionalClose = false;

    public SampleWorkerWebSocketClient(URI serverUri, String workerId) {
        this(serverUri, workerId, "SUCCESS");
    }

    public SampleWorkerWebSocketClient(URI serverUri, String workerId, String taskResultStatus) {
        super(withWorkerId(serverUri, workerId));
        this.workerId = workerId;
        this.taskResultStatus = normalizeConfiguredTaskResultStatus(taskResultStatus);
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-reconnect-scheduler-" + workerId);
            t.setDaemon(true);
            return t;
        });
        this.taskResponseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-task-response-scheduler-" + workerId);
            t.setDaemon(true);
            return t;
        });
        SampleCommandRuntime.initialize();
    }

    public SampleWorkerWebSocketClient(String workerId) {
        this(URI.create("ws://localhost:18088/ws"), workerId, "SUCCESS");
    }

    @Override
    public boolean connectBlocking() throws InterruptedException {
        return super.connectBlocking();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("[{}] Connected to server: {}", workerId, handshakedata.getHttpStatusMessage());
        reconnectAttempts.set(0);
        intentionalClose = false;
    }

    @Override
    public void onMessage(String message) {
        logger.debug("[{}] Received frame: {}", workerId, message);
        try {
            JsonObject frame = gson.fromJson(message, JsonObject.class);
            if (frame == null) {
                logger.warn("[{}] Ignoring non-object frame", workerId);
                return;
            }
            if (taskFrameHandler.isTaskDispatchFrame(frame)) {
                handleTaskMessage(frame);
                return;
            }
            if (taskFrameHandler.isTaskResultFrame(frame)) {
                logger.debug("[{}] Ignoring inbound canonical task result frame {}", workerId, readString(frame, "messageId"));
                return;
            }
            logger.warn("[{}] Ignoring unsupported worker frame shape", workerId);
        } catch (Exception e) {
            logger.error("[{}] Failed to parse or handle message: {}", workerId, e.getMessage(), e);
        }
    }

    private void handleTaskMessage(JsonObject taskMessage) {
        SampleClientState state = getSampleClientState();
        SampleWorkerTaskFrameHandler.TaskResponsePlan plan = taskFrameHandler.prepareResponse(
                taskMessage,
                workerId,
                taskResultStatus,
                state
        );
        if (plan == null) {
            return;
        }
        sendTaskResponse(plan.responseJson(), plan.messageId(), plan.delayMillis(), plan.disconnectWorkerId());
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("[{}] Disconnected from server. Code: {}, Reason: {}, Remote: {}", workerId, code, reason, remote);
        if (intentionalClose) {
            logger.info("[{}] Connection closed intentionally. Will not attempt to reconnect.", workerId);
            shutdownScheduler();
            return;
        }

        if (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
            long delay = (long) (INITIAL_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts.get()));
            delay = Math.min(delay, MAX_RECONNECT_DELAY_MS);

            logger.info("[{}] Will attempt to reconnect in {} seconds. Attempt: {}",
                    workerId, (delay / 1000.0), (reconnectAttempts.get() + 1));
            reconnectScheduler.schedule(() -> {
                logger.info("[{}] Attempting to reconnect... (Attempt {})",
                        workerId, reconnectAttempts.incrementAndGet());
                try {
                    reconnectBlocking();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("[{}] Reconnect attempt interrupted: {}", workerId, e.getMessage());
                } catch (Exception e) {
                    logger.error("[{}] Failed to reconnect: {}", workerId, e.getMessage());
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            logger.warn("[{}] Reached max reconnect attempts ({}). Giving up.", workerId, MAX_RECONNECT_ATTEMPTS);
            shutdownScheduler();
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("[{}] WebSocket error: {}", workerId, ex.getMessage(), ex);
    }

    public void closeConnection() {
        logger.info("[{}] Intentionally closing connection...", workerId);
        intentionalClose = true;
        try {
            super.closeBlocking();
        } catch (InterruptedException e) {
            logger.warn("[{}] Interrupted while closing connection.", workerId);
            Thread.currentThread().interrupt();
            super.close();
        }
        shutdownScheduler();
    }

    private void shutdownScheduler() {
        shutdownExecutor(taskResponseScheduler, "task response scheduler");
        shutdownExecutor(reconnectScheduler, "reconnect scheduler");
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

    private SampleClientState getSampleClientState() {
        SampleClientStateRegistry stateRegistry = SampleCommandRuntime.getService(SampleClientStateRegistry.class);
        return stateRegistry == null ? null : stateRegistry.getOrCreate(workerId);
    }

    private void sendTaskResponse(String responseJson, String messageId, long delayMillis, String disconnectWorkerId) {
        if (delayMillis <= 0L) {
            send(responseJson);
            logger.debug("[{}] Sent sample task response for messageId: {}", workerId, messageId);
            disconnectAfterTaskResultIfRequested(disconnectWorkerId);
            return;
        }

        logger.info("[{}] Scheduling sample task response for messageId={} after {} ms", workerId, messageId, delayMillis);
        taskResponseScheduler.schedule(() -> {
            if (!isOpen()) {
                logger.warn("[{}] Skip delayed task response because client is disconnected. messageId={}", workerId, messageId);
                return;
            }
            try {
                send(responseJson);
                logger.debug("[{}] Sent delayed sample task response for messageId: {}", workerId, messageId);
                disconnectAfterTaskResultIfRequested(disconnectWorkerId);
            } catch (Exception e) {
                logger.warn("[{}] Failed to send delayed sample task response for messageId={}: {}", workerId, messageId, e.getMessage());
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
            logger.info("[{}] Closing current worker connection after disconnect task result", workerId);
            closeConnection();
            return;
        }
        ClientSessionManager clientSessionManager = SampleCommandRuntime.getService(ClientSessionManager.class);
        if (clientSessionManager == null) {
            logger.warn("[{}] Cannot close target worker {} after disconnect task result because ClientSessionManager is not registered",
                    workerId, targetWorkerId);
            return;
        }
        SampleWorkerClient targetClient = clientSessionManager.getClient(targetWorkerId);
        if (targetClient == null) {
            logger.warn("[{}] Cannot close target worker {} after disconnect task result because client is missing",
                    workerId, targetWorkerId);
            return;
        }
        logger.info("[{}] Closing target worker {} after disconnect task result", workerId, targetWorkerId);
        try {
            targetClient.disconnect();
        } catch (Exception e) {
            logger.warn("[{}] Failed to close target worker {} after disconnect task result: {}", workerId, targetWorkerId, e.getMessage());
        }
    }

    @Override
    public String getWorkerId() {
        return workerId;
    }

    @Override
    public String adapterId() {
        return "websocket";
    }

    @Override
    public void connect(URI serverUri) throws Exception {
        if (isOpen()) {
            closeConnection();
        }
        super.connectBlocking();
    }

    @Override
    public void disconnect() throws Exception {
        closeConnection();
    }

    @Override
    public boolean isConnected() {
        return isOpen();
    }

    @Override
    public void sendMessage(String message) throws Exception {
        send(message);
    }

    private String readString(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeConfiguredTaskResultStatus(String taskResultStatus) {
        if (taskResultStatus == null || taskResultStatus.isBlank()) {
            return "SUCCESS";
        }
        String normalized = taskResultStatus.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }

    private static URI withWorkerId(URI serverUri, String workerId) {
        if (serverUri == null) {
            throw new IllegalArgumentException("serverUri must not be null");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        String existingQuery = serverUri.getRawQuery();
        String workerQuery = "workerId=" + workerId.trim();
        String mergedQuery = (existingQuery == null || existingQuery.isBlank())
                ? workerQuery
                : existingQuery + "&" + workerQuery;
        try {
            return new URI(
                    serverUri.getScheme(),
                    serverUri.getRawAuthority(),
                    serverUri.getRawPath(),
                    mergedQuery,
                    serverUri.getRawFragment()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to append workerId to serverUri", ex);
        }
    }
}

