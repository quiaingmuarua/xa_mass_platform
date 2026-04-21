package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.debug.ManualDebugChatProtocol;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.command.model.ApiResponse;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MassWebSocketClientImpl extends WebSocketClient implements MassWebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(MassWebSocketClientImpl.class);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;

    private final Gson gson = new Gson();
    private final ScheduledExecutorService reconnectScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final String workerId;
    private final String taskResultStatus;

    private boolean intentionalClose = false;
    private URI uri;

    public MassWebSocketClientImpl(URI serverUri, String workerId) {
        this(serverUri, workerId, "SUCCESS");
    }

    public MassWebSocketClientImpl(URI serverUri, String workerId, String taskResultStatus) {
        super(serverUri);
        this.workerId = workerId;
        this.taskResultStatus = normalizeTaskResultStatus(taskResultStatus);
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-reconnect-scheduler-" + workerId);
            t.setDaemon(true);
            return t;
        });
        this.uri = serverUri;
        MockCommandRuntime.initialize();
    }

    public MassWebSocketClientImpl(String workerId) {
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
            JsonObject json = gson.fromJson(message, JsonObject.class);
            MessageType msgType = MessageType.valueOf(json.get("msgType").getAsString().toUpperCase());

            switch (msgType) {
                case TASK:
                    handleTaskMessage(message);
                    break;
                case CONTROL:
                    handleControlMessage(message);
                    break;
                case PONG:
                    logger.debug("[{}] Pong received", workerId);
                    break;
                default:
                    logger.warn("[{}] Unhandled msgType: {}", workerId, msgType);
            }
        } catch (Exception e) {
            logger.error("[{}] Failed to parse or handle message: {}", workerId, e.getMessage(), e);
        }
    }

    private void handleTaskMessage(String message) {
        MassMessage taskMessage = gson.fromJson(message, MassMessage.class);
        if (taskMessage.isResponse()) {
            logger.debug("[{}] Ignoring task response frame for msgId: {}", workerId, taskMessage.getMsgId());
            return;
        }
        MessageContext originalContext = taskMessage.getContext();
        if (originalContext == null || originalContext.getTid() == null || originalContext.getTid().isBlank()) {
            logger.info("[{}] Received manual TASK message without tid, skipping task-result callback. msgId={}",
                    workerId, taskMessage.getMsgId());
            return;
        }
        TaskPayload taskPayload = gson.fromJson(taskMessage.getPayload(), TaskPayload.class);

        MassMessage response = new MassMessage();
        response.setMsgId(taskMessage.getMsgId());
        response.setResponse(true);
        response.setMsgType(MessageType.TASK);
        response.setFrom(MessageDirection.CLIENT);
        response.setSubMsgType("step");
        response.setProject(taskMessage.getProject());

        MessageContext responseContext = new MessageContext();
        responseContext.setConnRole(originalContext.getConnRole());
        responseContext.setTid(originalContext.getTid());
        responseContext.setRetryCount(originalContext.getRetryCount());
        responseContext.setWorkerId(workerId);
        response.setContext(responseContext);

        Map<String, Object> payloadMap = new HashMap<>();
        String stepId = (taskPayload != null && taskPayload.getSteps() != null && !taskPayload.getSteps().isEmpty())
                ? taskPayload.getSteps().get(0).getStepId()
                : "step-0-default";
        payloadMap.put("stepId", stepId);
        payloadMap.put("mockData", "Executed by mock client " + workerId);
        payloadMap.put("status", taskResultStatus);

        response.setPayload(gson.toJsonTree(payloadMap));

        send(gson.toJson(response));
        logger.debug("[{}] Sent mock task response for msgId: {}", workerId, response.getMsgId());
    }

    private void handleControlMessage(String message) {
        MassMessage controlMessage = gson.fromJson(message, MassMessage.class);
        logger.info("[{}] Received control message for debug chat. msgId={}, subMsgType={}",
                workerId, controlMessage.getMsgId(), controlMessage.getSubMsgType());

        MassMessage response = new MassMessage();
        response.setMsgId("manual-chat-" + workerId + "-" + System.currentTimeMillis());
        response.setResponse(true);
        response.setMsgType(MessageType.EVENT);
        response.setSubMsgType(ManualDebugChatProtocol.SUB_MSG_TYPE);
        response.setFrom(MessageDirection.CLIENT);
        response.setProject(controlMessage.getProject());

        MessageContext originalContext = controlMessage.getContext();
        MessageContext responseContext = new MessageContext();
        responseContext.setConnRole(originalContext != null ? originalContext.getConnRole() : SessionRoles.TASK_MESSAGES);
        responseContext.setWorkerId(workerId);
        response.setContext(responseContext);

        Map<String, Object> payloadMap = new HashMap<>();
        JsonObject commandRequest = extractCommandRequest(controlMessage);
        ApiResponse<?> commandResult = null;
        if (commandRequest != null) {
            commandResult = MockCommandRuntime.dispatch(commandRequest);
        }

        payloadMap.put(ManualDebugChatProtocol.MESSAGE_KIND_FIELD, ManualDebugChatProtocol.MESSAGE_KIND_ACK);
        payloadMap.put(ManualDebugChatProtocol.REPLY_TO_MESSAGE_ID_FIELD, controlMessage.getMsgId());
        payloadMap.put(ManualDebugChatProtocol.ACK_STATUS_FIELD, ManualDebugChatProtocol.ACK_STATUS_RECEIVED);
        payloadMap.put("message", commandResult == null
                ? "mock worker received manual debug message"
                : "mock worker executed command: " + commandRequest.get("event").getAsString());
        payloadMap.put(ManualDebugChatProtocol.WORKER_ID_FIELD, workerId);
        payloadMap.put(ManualDebugChatProtocol.RECEIVED_AT_FIELD, System.currentTimeMillis());
        payloadMap.put(ManualDebugChatProtocol.ECHO_PAYLOAD_FIELD, controlMessage.getPayload());
        payloadMap.put(ManualDebugChatProtocol.ECHO_SUB_MSG_TYPE_FIELD, controlMessage.getSubMsgType());
        payloadMap.put("commandExecuted", commandResult != null);
        if (commandResult != null) {
            payloadMap.put("commandEvent", commandRequest.get("event").getAsString());
            payloadMap.put("commandResult", commandResult);
        }
        response.setPayload(gson.toJsonTree(payloadMap));

        send(gson.toJson(response));
        logger.debug("[{}] Sent manual debug response for msgId: {}", workerId, controlMessage.getMsgId());
    }

    private JsonObject extractCommandRequest(MassMessage controlMessage) {
        JsonElement payload = controlMessage.getPayload();
        JsonObject commandRequest = null;
        if (payload == null || payload.isJsonNull()) {
            return null;
        }
        if (payload.isJsonObject()) {
            JsonObject payloadObject = payload.getAsJsonObject();
            if (payloadObject.has("event") && !payloadObject.get("event").isJsonNull()) {
                commandRequest = payloadObject.deepCopy();
            } else if (payloadObject.has("command") && payloadObject.get("command").isJsonObject()) {
                commandRequest = payloadObject.getAsJsonObject("command").deepCopy();
            } else if (payloadObject.has(ManualDebugChatProtocol.TEXT_FIELD)
                    && payloadObject.get(ManualDebugChatProtocol.TEXT_FIELD).isJsonPrimitive()) {
                commandRequest = parseCommandText(payloadObject.get(ManualDebugChatProtocol.TEXT_FIELD).getAsString());
            }
        } else if (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()) {
            commandRequest = parseCommandText(payload.getAsString());
        }

        if (commandRequest == null || !commandRequest.has("event") || commandRequest.get("event").isJsonNull()) {
            return null;
        }
        if (!commandRequest.has("workerId")) {
            commandRequest.addProperty("workerId", workerId);
        }
        if (!commandRequest.has("requestMsgId") && controlMessage.getMsgId() != null) {
            commandRequest.addProperty("requestMsgId", controlMessage.getMsgId());
        }
        if (!commandRequest.has("project") && controlMessage.getProject() != null) {
            commandRequest.addProperty("project", controlMessage.getProject());
        }
        return commandRequest;
    }

    private JsonObject parseCommandText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            logger.warn("[{}] Ignoring invalid command JSON text: {}", workerId, e.getMessage());
            return null;
        }
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
        if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
            List<Runnable> cancelledTasks = reconnectScheduler.shutdownNow();
            try {
                if (!reconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("[{}] Reconnect scheduler did not terminate cleanly within timeout.", workerId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.info("[{}] Reconnect scheduler shut down. Cancelled {} queued tasks.", workerId, cancelledTasks.size());
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    private String normalizeTaskResultStatus(String taskResultStatus) {
        if (taskResultStatus == null || taskResultStatus.isBlank()) {
            return "SUCCESS";
        }
        String normalized = taskResultStatus.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }

    @Override
    public void connect(URI serverUri) throws Exception {
        if (isOpen()) {
            closeConnection();
        }
        this.uri = serverUri;
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
}
