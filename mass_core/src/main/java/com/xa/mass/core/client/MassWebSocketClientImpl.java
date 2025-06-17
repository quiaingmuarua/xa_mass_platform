package com.xa.mass.core.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.core.model.message.*;
import com.xa.mass.core.model.message.payload.TaskPayload;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MassWebSocketClientImpl extends WebSocketClient implements MassWebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(MassWebSocketClientImpl.class);
    private static final int MAX_RECONNECT_ATTEMPTS = 10; // 最大重连次
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000; // 初始重连延迟 (1
    private static final long MAX_RECONNECT_DELAY_MS = 60000; // 最大重连延(60
    private final Gson gson = new Gson();
    private final ScheduledExecutorService reconnectScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final String deviceId; // 每个客户端实例持有一个deviceId
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

    // 为了方便，可以保留一个默认构造函数或提供一个工厂方
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
        reconnectAttempts.set(0); // 连接成功，重置重连尝试次
        intentionalClose = false; // 重置主动关闭标记

        // 构ping 消息
        BaseMessage<Void> ping = new BaseMessage<>();
        ping.setMsgId("ping-" + deviceId + "-" + System.currentTimeMillis());
        ping.setMsgType(MessageType.PING);
        ping.setFrom(MessageDirection.CLIENT);
        ping.setSubMsgType("heartbeat");

        MessageContext ctx = new MessageContext();
        ctx.setDeviceId(this.deviceId); // 使用实例的deviceId
        ctx.setConnRole("messaegs_task");
        ping.setContext(ctx);

        send(gson.toJson(ping));
        logger.info("📤 [{}] Sent PING message.", deviceId);
    }

    @Override
    public void onMessage(String message) {
        logger.info("📩 [{}] Received: {}", deviceId, message);
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            MessageType msgType = MessageType.valueOf(json.get("msgType").getAsString().toUpperCase());

            switch (msgType) {
                case TASK:
                    handleTaskMessage(message);
                    break;
                case PONG:
                    logger.info("🫶 [{}] Pong received.", deviceId);
                    break;
                default:
                    logger.warn("⚠️ [{}] Unhandled msgType: {}", deviceId, msgType);
            }
        } catch (Exception e) {
            logger.error("[{}] Failed to parse or handle message: {}", deviceId, e.getMessage(), e);
        }
    }

    private void handleTaskMessage(String message) {
        Type taskMsgType = new TypeToken<BaseMessage<TaskPayload>>() {
        }.getType();
        BaseMessage<TaskPayload> taskMessage = gson.fromJson(message, taskMsgType);

        BaseMessage<Map<String, Object>> response = new BaseMessage<>();
        response.setMsgId(taskMessage.getMsgId()); // 回复时使用收到的msgId
        response.setMsgType(MessageType.RESPONSE);
        response.setFrom(MessageDirection.CLIENT);
        response.setSubMsgType("step"); // 或者根据taskMessage.getContext().getResponseLevel()

        // 透传 context
        MessageContext originalContext = taskMessage.getContext();
        if (originalContext != null) {
            MessageContext responseContext = new MessageContext();
            responseContext.setConnRole(originalContext.getConnRole());
            responseContext.setTaskId(originalContext.getTaskId());
            responseContext.setRetryCount(originalContext.getRetryCount());
            responseContext.setResponseLevel(originalContext.getResponseLevel());
            responseContext.setDeviceId(this.deviceId); // 确保响应中是我们自己的deviceId
            // 如果需要，可以从原始context复制更多字段
            response.setContext(responseContext);
        }


        // 构payload
        Map<String, Object> payloadMap = new HashMap<>();
        TaskPayload taskPayload = taskMessage.getPayload();
        String stepId = (taskPayload != null && taskPayload.getSteps() != null && !taskPayload.getSteps().isEmpty())
                ? taskPayload.getSteps().get(0).getStepId()
                : "step-0-default"; // 提供一个默认值以防万一
        payloadMap.put("stepId", stepId);
        payloadMap.put("mockData", "Executed by mock client " + this.deviceId);
        payloadMap.put("status", "SUCCESS");


        // 构result
        MessageResult resMeta = new MessageResult();
        resMeta.setCode(200);
        resMeta.setMessage("Mock execution successful for step " + stepId + " by " + this.deviceId);

        response.setPayload(payloadMap);
        response.setResult(resMeta);

        send(gson.toJson(response));
        logger.info("📤 [{}] Sent mock task response for msgId: {}", deviceId, response.getMsgId());
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("🔌 [{}] Disconnected from server. Code: {}, Reason: {}, Remote: {}", deviceId, code, reason, remote);
        if (intentionalClose) {
            logger.info("🔌 [{}] Connection closed intentionally. Will not attempt to reconnect.", deviceId);
            shutdownScheduler();
            return;
        }

        if (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
            long delay = (long) (INITIAL_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts.get()));
            delay = Math.min(delay, MAX_RECONNECT_DELAY_MS); // 确保延迟不超过最大

            logger.info("🔌 [{}] Will attempt to reconnect in {} seconds. Attempt: {}", deviceId, (delay / 1000.0), (reconnectAttempts.get() + 1));
            reconnectScheduler.schedule(() -> {
                logger.info("🔌 [{}] Attempting to reconnect... (Attempt {})", deviceId, reconnectAttempts.incrementAndGet());
                try {
                    reconnectBlocking();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("🔌 [{}] Reconnect attempt interrupted: {}", deviceId, e.getMessage());
                } catch (Exception e) {
                    logger.error("🔌 [{}] Failed to reconnect: {}", deviceId, e.getMessage());
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            logger.warn("🔌 [{}] Reached max reconnect attempts ({}). Giving up.", deviceId, MAX_RECONNECT_ATTEMPTS);
            shutdownScheduler();
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("[{}] WebSocket error: {}", deviceId, ex.getMessage(), ex);
    }

    public void closeConnection() {
        logger.info("🔌 [{}] Intentionally closing connection...", deviceId);
        intentionalClose = true;
        try {
            super.closeBlocking(); // 使用 closeBlocking 确保连接关闭
        } catch (InterruptedException e) {
            logger.warn("[{}] Interrupted while closing connection.", deviceId);
            Thread.currentThread().interrupt();
            super.close(); // Fallback to non-blocking close
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
            logger.info("🔌 [{}] Reconnect scheduler shut down.", deviceId);
        }
    }

    public String getDeviceId() {
        return deviceId;
    }

    // MassWebSocketClient 接口实现
    @Override
    public void connect(URI serverUri) throws Exception {
        // 只支持重连到新 URI，需关闭当前连接再重连
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

    // 移除原有main 方法，启动逻辑将移MassClientApplication 或专门的启动
}
