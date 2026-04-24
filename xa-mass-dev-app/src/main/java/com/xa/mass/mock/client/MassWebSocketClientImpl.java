package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.command.mock.MockClientState;
import com.xa.mass.mock.command.mock.MockClientStateRegistry;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MassWebSocketClientImpl extends WebSocketClient implements MassWebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(MassWebSocketClientImpl.class);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;
    private static final long DEFAULT_TASK_RESPONSE_BASE_DELAY_MS = 15L;
    private static final long DEFAULT_TASK_RESPONSE_JITTER_MS = 35L;

    private final Gson gson = new Gson();
    private final ScheduledExecutorService reconnectScheduler;
    private final ScheduledExecutorService taskResponseScheduler;
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
        this.taskResponseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-task-response-scheduler-" + workerId);
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
        int stepCount = taskPayload != null && taskPayload.getSteps() != null ? taskPayload.getSteps().size() : 0;
        MockClientState state = getMockClientState();
        String resolvedStatus = resolveTaskResultStatus(state);
        long delayMillis = resolveTaskResponseDelayMillis(taskMessage, taskPayload, state, resolvedStatus);
        long startedAtEpochMillis = System.currentTimeMillis();
        long finishedAtEpochMillis = startedAtEpochMillis + delayMillis;
        payloadMap.put("stepId", stepId);
        payloadMap.put("mockData", "Executed by mock client " + workerId);
        payloadMap.put("status", resolvedStatus);
        payloadMap.put("execution", buildExecutionSnapshot(
                originalContext,
                taskMessage,
                stepCount,
                delayMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis,
                resolvedStatus
        ));
        payloadMap.put("workerProfile", buildWorkerProfile());

        response.setPayload(gson.toJsonTree(payloadMap));
        if (state != null && state.shouldDropTaskResponse()) {
            logger.info("[{}] Dropped mock task response for msgId={} due to mock state {}", workerId,
                    response.getMsgId(), state.snapshot());
            return;
        }

        sendTaskResponse(response, delayMillis);
    }

    private void handleControlMessage(String message) {
        MassMessage controlMessage = gson.fromJson(message, MassMessage.class);
        logger.info("[{}] Received control message. msgId={}, subMsgType={}",
                workerId, controlMessage.getMsgId(), controlMessage.getSubMsgType());

        MassMessage response = new MassMessage();
        response.setMsgId(controlMessage.getMsgId());
        response.setResponse(true);
        response.setMsgType(MessageType.CONTROL);
        response.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
        response.setFrom(MessageDirection.CLIENT);
        response.setProject(controlMessage.getProject());

        MessageContext originalContext = controlMessage.getContext();
        MessageContext responseContext = new MessageContext();
        responseContext.setConnRole(originalContext != null ? originalContext.getConnRole() : SessionRoles.TASK_MESSAGES);
        responseContext.setWorkerId(workerId);
        response.setContext(responseContext);

        Map<String, Object> payloadMap = new HashMap<>();
        JsonObject commandRequest = extractCommandRequest(controlMessage);
        CommandResponse<?> commandResult = null;
        if (commandRequest != null) {
            commandResult = MockCommandRuntime.dispatch(commandRequest);
        }

        payloadMap.put(WorkerControlMessageProtocol.MESSAGE_KIND_FIELD, WorkerControlMessageProtocol.MESSAGE_KIND_ACK);
        payloadMap.put(WorkerControlMessageProtocol.REPLY_TO_MESSAGE_ID_FIELD, controlMessage.getMsgId());
        payloadMap.put(WorkerControlMessageProtocol.ACK_STATUS_FIELD, WorkerControlMessageProtocol.ACK_STATUS_RECEIVED);
        payloadMap.put("message", resolveAckMessage(controlMessage, commandRequest, commandResult));
        payloadMap.put(WorkerControlMessageProtocol.WORKER_ID_FIELD, workerId);
        payloadMap.put(WorkerControlMessageProtocol.RECEIVED_AT_FIELD, System.currentTimeMillis());
        payloadMap.put(WorkerControlMessageProtocol.ECHO_PAYLOAD_FIELD, controlMessage.getPayload());
        payloadMap.put(WorkerControlMessageProtocol.ECHO_SUB_MSG_TYPE_FIELD, controlMessage.getSubMsgType());
        payloadMap.put("commandExecuted", commandResult != null);
        JsonObject eventEnvelope = extractEventEnvelope(controlMessage);
        if (eventEnvelope != null
                && eventEnvelope.has(WorkerControlEventProtocol.REQUEST_ID_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).isJsonNull()) {
            payloadMap.put(
                    WorkerControlEventProtocol.REQUEST_ID_FIELD,
                    eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString()
            );
        }
        if (commandResult != null) {
            payloadMap.put("commandEvent", commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString());
            payloadMap.put("commandResult", commandResult);
        }
        String resolvedEventCode = resolveInboundEventCode(controlMessage, commandRequest, eventEnvelope);
        if (resolvedEventCode != null) {
            payloadMap.put(WorkerControlEventProtocol.EVENT_FIELD, resolvedEventCode);
        }
        response.setPayload(gson.toJsonTree(payloadMap));

        send(gson.toJson(response));
        logger.debug("[{}] Sent worker control response for msgId: {}", workerId, controlMessage.getMsgId());
        disconnectAfterAckIfRequested(commandResult);
    }

    private JsonObject extractCommandRequest(MassMessage controlMessage) {
        JsonElement payload = controlMessage.getPayload();
        JsonObject commandRequest = null;
        if (payload == null || payload.isJsonNull()) {
            return null;
        }
        if (payload.isJsonObject()) {
            JsonObject payloadObject = payload.getAsJsonObject();
            JsonObject eventEnvelope = extractEventEnvelope(controlMessage);
            if (eventEnvelope != null) {
                commandRequest = buildCommandRequestFromEventEnvelope(eventEnvelope);
            } else if (payloadObject.has(WorkerControlEventProtocol.EVENT_FIELD)
                    && !payloadObject.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
                commandRequest = payloadObject.deepCopy();
            } else if (payloadObject.has("command") && payloadObject.get("command").isJsonObject()) {
                commandRequest = payloadObject.getAsJsonObject("command").deepCopy();
            } else if (payloadObject.has(WorkerControlMessageProtocol.TEXT_FIELD)
                    && payloadObject.get(WorkerControlMessageProtocol.TEXT_FIELD).isJsonPrimitive()) {
                commandRequest = parseCommandText(payloadObject.get(WorkerControlMessageProtocol.TEXT_FIELD).getAsString());
            }
        } else if (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()) {
            commandRequest = parseCommandText(payload.getAsString());
        }

        if (commandRequest == null
                || !commandRequest.has(WorkerControlEventProtocol.EVENT_FIELD)
                || commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
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

    private JsonObject extractEventEnvelope(MassMessage controlMessage) {
        JsonElement payload = controlMessage.getPayload();
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject payloadObject = payload.getAsJsonObject();
        if (!WorkerControlEventProtocol.SUB_MSG_TYPE.equals(controlMessage.getSubMsgType())) {
            return null;
        }
        if (!payloadObject.has(WorkerControlEventProtocol.EVENT_FIELD)
                || payloadObject.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return null;
        }
        return payloadObject;
    }

    private JsonObject buildCommandRequestFromEventEnvelope(JsonObject eventEnvelope) {
        JsonObject commandRequest = new JsonObject();
        commandRequest.add(
                WorkerControlEventProtocol.EVENT_FIELD,
                eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).deepCopy()
        );
        if (eventEnvelope.has(WorkerControlEventProtocol.PAYLOAD_FIELD)
                && eventEnvelope.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()) {
            JsonObject payloadObject = eventEnvelope.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD);
            for (Map.Entry<String, JsonElement> entry : payloadObject.entrySet()) {
                if (WorkerControlEventProtocol.EVENT_FIELD.equals(entry.getKey())) {
                    continue;
                }
                commandRequest.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        if (eventEnvelope.has(WorkerControlEventProtocol.REQUEST_ID_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).isJsonNull()) {
            commandRequest.add(
                    WorkerControlEventProtocol.REQUEST_ID_FIELD,
                    eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).deepCopy()
            );
        }
        if (eventEnvelope.has(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                && eventEnvelope.get(WorkerControlEventProtocol.PRINCIPAL_FIELD).isJsonObject()) {
            JsonObject principal = eventEnvelope.getAsJsonObject(WorkerControlEventProtocol.PRINCIPAL_FIELD);
            if (principal.has(WorkerControlEventProtocol.CLIENT_ID_FIELD)
                    && !principal.get(WorkerControlEventProtocol.CLIENT_ID_FIELD).isJsonNull()) {
                commandRequest.add(
                        WorkerControlEventProtocol.CLIENT_ID_FIELD,
                        principal.get(WorkerControlEventProtocol.CLIENT_ID_FIELD).deepCopy()
                );
            }
            if (principal.has(WorkerControlEventProtocol.USER_ID_FIELD)
                    && !principal.get(WorkerControlEventProtocol.USER_ID_FIELD).isJsonNull()) {
                commandRequest.add(
                        WorkerControlEventProtocol.USER_ID_FIELD,
                        principal.get(WorkerControlEventProtocol.USER_ID_FIELD).deepCopy()
                );
            }
        }
        return commandRequest;
    }

    private String resolveAckMessage(MassMessage controlMessage,
                                     JsonObject commandRequest,
                                     CommandResponse<?> commandResult) {
        if (commandResult != null) {
            return "mock worker executed command: "
                    + commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        JsonObject eventEnvelope = extractEventEnvelope(controlMessage);
        if (eventEnvelope != null
                && eventEnvelope.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return "mock worker received event: "
                    + eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        return "mock worker received control message";
    }

    private String resolveInboundEventCode(MassMessage controlMessage,
                                           JsonObject commandRequest,
                                           JsonObject eventEnvelope) {
        if (eventEnvelope != null
                && eventEnvelope.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        if (commandRequest != null
                && commandRequest.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        return controlMessage.getSubMsgType();
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

    private String resolveTaskResultStatus(MockClientState state) {
        if (state == null) {
            return taskResultStatus;
        }
        return normalizeTaskResultStatus(state.resolveTaskResultStatus(taskResultStatus));
    }

    private long resolveTaskResponseDelayMillis(MassMessage taskMessage,
                                                TaskPayload taskPayload,
                                                MockClientState state,
                                                String taskStatus) {
        if (state != null && state.getTaskResponseDelayMillis() > 0L) {
            return state.getTaskResponseDelayMillis();
        }
        int stepCount = taskPayload != null && taskPayload.getSteps() != null ? taskPayload.getSteps().size() : 0;
        int stableHash = Objects.hash(workerId, taskMessage.getMsgId(), taskMessage.getProject(), stepCount);
        long jitter = Math.floorMod(stableHash, (int) DEFAULT_TASK_RESPONSE_JITTER_MS + 1);
        long failurePenalty = "FAILED".equals(taskStatus) ? 10L : 0L;
        return DEFAULT_TASK_RESPONSE_BASE_DELAY_MS + jitter + Math.max(0, stepCount - 1) * 5L + failurePenalty;
    }

    private Map<String, Object> buildExecutionSnapshot(MessageContext originalContext,
                                                       MassMessage taskMessage,
                                                       int stepCount,
                                                       long delayMillis,
                                                       long startedAtEpochMillis,
                                                       long finishedAtEpochMillis,
                                                       String taskStatus) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("transport", "websocket");
        execution.put("startedAtEpochMs", startedAtEpochMillis);
        execution.put("finishedAtEpochMs", finishedAtEpochMillis);
        execution.put("startedAt", Instant.ofEpochMilli(startedAtEpochMillis).toString());
        execution.put("finishedAt", Instant.ofEpochMilli(finishedAtEpochMillis).toString());
        execution.put("durationMs", delayMillis);
        execution.put("stepCount", stepCount);
        execution.put("taskStatus", taskStatus);
        Integer retryCount = originalContext != null ? originalContext.getRetryCount() : null;
        execution.put("retryCount", retryCount == null ? 0 : retryCount);
        execution.put("project", taskMessage.getProject());
        execution.put("messageId", taskMessage.getMsgId());
        execution.put("taskId", originalContext != null ? originalContext.getTid() : null);
        return execution;
    }

    private Map<String, Object> buildWorkerProfile() {
        Map<String, Object> workerProfile = new LinkedHashMap<>();
        workerProfile.put("workerId", workerId);
        workerProfile.put("runtime", "mock-websocket-client");
        workerProfile.put("host", "mock-host-" + workerId);
        workerProfile.put("os", System.getProperty("os.name"));
        workerProfile.put("javaVersion", System.getProperty("java.version"));
        workerProfile.put("processId", ProcessHandle.current().pid());
        return workerProfile;
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
        MassWebSocketClient targetClient = clientSessionManager.getClient(targetWorkerId);
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
