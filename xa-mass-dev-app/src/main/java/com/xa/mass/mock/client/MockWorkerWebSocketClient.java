package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.command.mock.MockClientState;
import com.xa.mass.mock.command.mock.MockClientStateRegistry;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
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

public class MockWorkerWebSocketClient extends WebSocketClient implements MockWorkerClient {
    private static final Logger logger = LoggerFactory.getLogger(MockWorkerWebSocketClient.class);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;

    private final Gson gson = new Gson();
    private final ScheduledExecutorService reconnectScheduler;
    private final ScheduledExecutorService taskResponseScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final String workerId;
    private final MockWorkerTaskFrameHandler taskFrameHandler = new MockWorkerTaskFrameHandler();
    private final MockWorkerControlFrameHandler controlFrameHandler = new MockWorkerControlFrameHandler();
    private final String taskResultStatus;

    private boolean intentionalClose = false;

    public MockWorkerWebSocketClient(URI serverUri, String workerId) {
        this(serverUri, workerId, "SUCCESS");
    }

    public MockWorkerWebSocketClient(URI serverUri, String workerId, String taskResultStatus) {
        super(serverUri);
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
        MockCommandRuntime.initialize();
    }

    public MockWorkerWebSocketClient(String workerId) {
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

        MassMessage ping = new MassMessage();
        ping.setMsgId("ping-" + workerId + "-" + System.currentTimeMillis());
        ping.setMsgType(MessageType.PING);
        ping.setFrom(MessageDirection.CLIENT);
        ping.setSubMsgType("heartbeat");

        MessageContext ctx = new MessageContext();
        ctx.setWorkerId(workerId);
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        ping.setContext(ctx);

        send(gson.toJson(ping));
        logger.debug("[{}] Sent PING message", workerId);
    }

    @Override
    public void onMessage(String message) {
        logger.debug("[{}] Received frame: {}", workerId, message);
        try {
            MassMessage frame = gson.fromJson(message, MassMessage.class);
            if (frame == null || frame.getMsgType() == null) {
                logger.warn("[{}] Ignoring frame without msgType", workerId);
                return;
            }
            switch (frame.getMsgType()) {
                case TASK:
                    handleTaskMessage(frame);
                    break;
                case CONTROL:
                    handleControlMessage(frame);
                    break;
                case PONG:
                    logger.debug("[{}] Pong received", workerId);
                    break;
                default:
                    logger.warn("[{}] Unhandled msgType: {}", workerId, frame.getMsgType());
            }
        } catch (Exception e) {
            logger.error("[{}] Failed to parse or handle message: {}", workerId, e.getMessage(), e);
        }
    }

    private void handleTaskMessage(MassMessage taskMessage) {
        MockClientState state = getMockClientState();
        MockWorkerTaskFrameHandler.TaskResponsePlan plan = taskFrameHandler.prepareResponse(
                taskMessage,
                workerId,
                taskResultStatus,
                state
        );
        if (plan == null) {
            return;
        }
        sendTaskResponse(plan.response(), plan.delayMillis());
    }

    private void handleControlMessage(MassMessage controlMessage) {
        MockWorkerControlFrameHandler.ControlResponsePlan plan = controlFrameHandler.prepareResponse(controlMessage, workerId);
        if (plan == null) {
            return;
        }
        send(gson.toJson(plan.response()));
        logger.debug("[{}] Sent worker control response for msgId: {}", workerId, controlMessage.getMsgId());
        disconnectAfterAckIfRequested(plan.commandResult());
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

    private MockClientState getMockClientState() {
        MockClientStateRegistry stateRegistry = MockCommandRuntime.getService(MockClientStateRegistry.class);
        return stateRegistry == null ? null : stateRegistry.getOrCreate(workerId);
    }

    private void sendTaskResponse(MassMessage response, long delayMillis) {
        if (delayMillis <= 0L) {
            send(gson.toJson(response));
            logger.debug("[{}] Sent mock task response for msgId: {}", workerId, response.getMsgId());
            return;
        }

        logger.info("[{}] Scheduling mock task response for msgId={} after {} ms",
                workerId, response.getMsgId(), delayMillis);
        taskResponseScheduler.schedule(() -> {
            if (!isOpen()) {
                logger.warn("[{}] Skip delayed task response because client is disconnected. msgId={}",
                        workerId, response.getMsgId());
                return;
            }
            try {
                send(gson.toJson(response));
                logger.debug("[{}] Sent delayed mock task response for msgId: {}", workerId, response.getMsgId());
            } catch (Exception e) {
                logger.warn("[{}] Failed to send delayed mock task response for msgId={}: {}",
                        workerId, response.getMsgId(), e.getMessage());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void disconnectAfterAckIfRequested(CommandResponse<?> commandResult) {
        String targetWorkerId = resolveDisconnectWorkerId(commandResult);
        if (targetWorkerId == null || targetWorkerId.isBlank()) {
            return;
        }
        taskResponseScheduler.schedule(() -> closeTargetWorker(targetWorkerId), 100, TimeUnit.MILLISECONDS);
    }

    private String resolveDisconnectWorkerId(CommandResponse<?> commandResult) {
        if (commandResult == null || !commandResult.isSuccess() || !(commandResult.getData() instanceof Map<?, ?> data)) {
            return null;
        }
        Object disconnectAfterAck = data.get("disconnectAfterAck");
        if (!Boolean.TRUE.equals(disconnectAfterAck)) {
            return null;
        }
        Object disconnectWorkerId = data.get("disconnectWorkerId");
        return disconnectWorkerId == null ? workerId : String.valueOf(disconnectWorkerId);
    }

    private void closeTargetWorker(String targetWorkerId) {
        if (workerId.equals(targetWorkerId)) {
            logger.info("[{}] Closing current worker connection after command acknowledgement", workerId);
            closeConnection();
            return;
        }
        ClientSessionManager clientSessionManager = MockCommandRuntime.getService(ClientSessionManager.class);
        if (clientSessionManager == null) {
            logger.warn("[{}] Cannot close target worker {} after ack because ClientSessionManager is not registered",
                    workerId, targetWorkerId);
            return;
        }
        MockWorkerClient targetClient = clientSessionManager.getClient(targetWorkerId);
        if (targetClient == null) {
            logger.warn("[{}] Cannot close target worker {} after ack because client is missing",
                    workerId, targetWorkerId);
            return;
        }
        logger.info("[{}] Closing target worker {} after command acknowledgement", workerId, targetWorkerId);
        try {
            targetClient.disconnect();
        } catch (Exception e) {
            logger.warn("[{}] Failed to close target worker {} after ack: {}",
                    workerId, targetWorkerId, e.getMessage());
        }
    }

    @Override
    public String getWorkerId() {
        return workerId;
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

    private String normalizeConfiguredTaskResultStatus(String taskResultStatus) {
        if (taskResultStatus == null || taskResultStatus.isBlank()) {
            return "SUCCESS";
        }
        String normalized = taskResultStatus.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }
}
