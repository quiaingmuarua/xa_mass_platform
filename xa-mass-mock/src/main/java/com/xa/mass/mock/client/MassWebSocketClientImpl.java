package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.session.SessionRoles;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
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
    private final String deviceId;

    private boolean intentionalClose = false;
    private URI uri;

    public MassWebSocketClientImpl(URI serverUri, String deviceId) {
        super(serverUri);
        this.deviceId = deviceId;
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-reconnect-scheduler-" + deviceId);
            t.setDaemon(true);
            return t;
        });
        this.uri = serverUri;
    }

    public MassWebSocketClientImpl(String deviceId) {
        this(URI.create("ws://localhost:8088/ws"), deviceId);
    }

    @Override
    public boolean connectBlocking() throws InterruptedException {
        return super.connectBlocking();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("[{}] Connected to server: {}", deviceId, handshakedata.getHttpStatusMessage());
        reconnectAttempts.set(0);
        intentionalClose = false;

        MassMessage ping = new MassMessage();
        ping.setMsgId("ping-" + deviceId + "-" + System.currentTimeMillis());
        ping.setMsgType(MessageType.PING);
        ping.setFrom(MessageDirection.CLIENT);
        ping.setSubMsgType("heartbeat");

        MessageContext ctx = new MessageContext();
        ctx.setDeviceId(deviceId);
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        ping.setContext(ctx);

        send(gson.toJson(ping));
        logger.info("[{}] Sent PING message", deviceId);
    }

    @Override
    public void onMessage(String message) {
        logger.info("[{}] Received: {}", deviceId, message);
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            MessageType msgType = MessageType.valueOf(json.get("msgType").getAsString().toUpperCase());

            switch (msgType) {
                case TASK:
                    handleTaskMessage(message);
                    break;
                case PONG:
                    logger.info("[{}] Pong received", deviceId);
                    break;
                default:
                    logger.warn("[{}] Unhandled msgType: {}", deviceId, msgType);
            }
        } catch (Exception e) {
            logger.error("[{}] Failed to parse or handle message: {}", deviceId, e.getMessage(), e);
        }
    }

    private void handleTaskMessage(String message) {
        MassMessage taskMessage = gson.fromJson(message, MassMessage.class);
        if (taskMessage.isResponse()) {
            logger.info("[{}] Ignoring task response frame for msgId: {}", deviceId, taskMessage.getMsgId());
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

        MessageContext originalContext = taskMessage.getContext();
        if (originalContext != null) {
            MessageContext responseContext = new MessageContext();
            responseContext.setConnRole(originalContext.getConnRole());
            responseContext.setTid(originalContext.getTid());
            responseContext.setRetryCount(originalContext.getRetryCount());
            responseContext.setDeviceId(deviceId);
            response.setContext(responseContext);
        }

        Map<String, Object> payloadMap = new HashMap<>();
        String stepId = (taskPayload != null && taskPayload.getSteps() != null && !taskPayload.getSteps().isEmpty())
                ? taskPayload.getSteps().get(0).getStepId()
                : "step-0-default";
        payloadMap.put("stepId", stepId);
        payloadMap.put("mockData", "Executed by mock client " + deviceId);
        payloadMap.put("status", "SUCCESS");

        response.setPayload(gson.toJsonTree(payloadMap));

        send(gson.toJson(response));
        logger.info("[{}] Sent mock task response for msgId: {}", deviceId, response.getMsgId());
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("[{}] Disconnected from server. Code: {}, Reason: {}, Remote: {}", deviceId, code, reason, remote);
        if (intentionalClose) {
            logger.info("[{}] Connection closed intentionally. Will not attempt to reconnect.", deviceId);
            shutdownScheduler();
            return;
        }

        if (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
            long delay = (long) (INITIAL_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts.get()));
            delay = Math.min(delay, MAX_RECONNECT_DELAY_MS);

            logger.info("[{}] Will attempt to reconnect in {} seconds. Attempt: {}",
                    deviceId, (delay / 1000.0), (reconnectAttempts.get() + 1));
            reconnectScheduler.schedule(() -> {
                logger.info("[{}] Attempting to reconnect... (Attempt {})",
                        deviceId, reconnectAttempts.incrementAndGet());
                try {
                    reconnectBlocking();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("[{}] Reconnect attempt interrupted: {}", deviceId, e.getMessage());
                } catch (Exception e) {
                    logger.error("[{}] Failed to reconnect: {}", deviceId, e.getMessage());
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            logger.warn("[{}] Reached max reconnect attempts ({}). Giving up.", deviceId, MAX_RECONNECT_ATTEMPTS);
            shutdownScheduler();
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("[{}] WebSocket error: {}", deviceId, ex.getMessage(), ex);
    }

    public void closeConnection() {
        logger.info("[{}] Intentionally closing connection...", deviceId);
        intentionalClose = true;
        try {
            super.closeBlocking();
        } catch (InterruptedException e) {
            logger.warn("[{}] Interrupted while closing connection.", deviceId);
            Thread.currentThread().interrupt();
            super.close();
        }
        shutdownScheduler();
    }

    private void shutdownScheduler() {
        if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
            reconnectScheduler.shutdown();
            try {
                if (!reconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                reconnectScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("[{}] Reconnect scheduler shut down.", deviceId);
        }
    }

    public String getDeviceId() {
        return deviceId;
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
