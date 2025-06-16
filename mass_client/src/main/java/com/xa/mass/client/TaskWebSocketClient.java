package com.xa.mass.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.model.message.*;

import com.xa.mass.model.message.payload.TaskPayload;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskWebSocketClient extends WebSocketClient {

    private final Gson gson = new Gson();
    private final ScheduledExecutorService reconnectScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 10; // 最大重连次数
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000; // 初始重连延迟 (1秒)
    private static final long MAX_RECONNECT_DELAY_MS = 60000; // 最大重连延迟 (60秒)
    private boolean intentionalClose = false;


    public TaskWebSocketClient() {
        super(URI.create("ws://localhost:8088/ws"));
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-reconnect-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("✅ Connected to server: " + handshakedata.getHttpStatusMessage());
        reconnectAttempts.set(0); // 连接成功，重置重连尝试次数
        intentionalClose = false; // 重置主动关闭标记

        // 构造 ping 消息
        BaseMessage<Void> ping = new BaseMessage<>();
        ping.setMsgId("ping-" + System.currentTimeMillis());
        ping.setMsgType(MessageType.PING);
        ping.setFrom(MessageDirection.CLIENT);
        ping.setSubMsgType("heartbeat");

        MessageContext ctx = new MessageContext();
        ctx.setDeviceId("mock_device_001");
        ctx.setConnRole("messaegs_task");
        ping.setContext(ctx);

        send(gson.toJson(ping));
    }

    @Override
    public void onMessage(String message) {
        System.out.println("📩 Received: " + message);
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            MessageType msgType = MessageType.valueOf(json.get("msgType").getAsString().toUpperCase());

            switch (msgType) {
                case TASK:
                    handleTaskMessage(message);
                    break;
                case PONG:
                    System.out.println("🫶 Pong received.");
                    break;
                default:
                    System.out.println("⚠️ Unhandled msgType: " + msgType);
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to parse or handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleTaskMessage(String message) {
        Type taskMsgType = new TypeToken<BaseMessage<TaskPayload>>() {}.getType();
        BaseMessage<TaskPayload> taskMessage = gson.fromJson(message, taskMsgType);

        BaseMessage<Map<String, Object>> response = new BaseMessage<>();
        response.setMsgId(taskMessage.getMsgId());
        response.setMsgType(MessageType.RESPONSE);
        response.setFrom(MessageDirection.CLIENT);
        response.setSubMsgType("step");

        // 透传 context
        response.setContext(taskMessage.getContext());

        // 构造 payload
        Map<String, Object> payload = new HashMap<>();
        TaskPayload taskPayload = taskMessage.getPayload();
        String stepId = (taskPayload != null && taskPayload.getSteps() != null && !taskPayload.getSteps().isEmpty())
                ? taskPayload.getSteps().get(0).getStepId()
                : "step-0";
        payload.put("stepId", stepId);
        payload.put("mockData", "Executed by mock client");

        // 构造 result
        MessageResult resMeta = new MessageResult();
        resMeta.setCode(200);
        resMeta.setMessage("Mock execution successful");

        response.setPayload(payload);
        response.setResult(resMeta);

        send(gson.toJson(response));
        System.out.println("📤 Sent mock task response.");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("🔌 Disconnected from server. Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
        if (intentionalClose) {
            System.out.println("🔌 Connection closed intentionally. Will not attempt to reconnect.");
            shutdownScheduler();
            return;
        }

        if (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
            long delay = (long) (INITIAL_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts.get()));
            delay = Math.min(delay, MAX_RECONNECT_DELAY_MS); // 确保延迟不超过最大值

            System.out.println("🔌 Will attempt to reconnect in " + (delay / 1000.0) + " seconds. Attempt: " + (reconnectAttempts.get() + 1));
            reconnectScheduler.schedule(() -> {
                System.out.println("🔌 Attempting to reconnect... (Attempt " + reconnectAttempts.incrementAndGet() + ")");
                try {
                    reconnectBlocking(); // 或者使用 reconnect() 如果你不想阻塞当前调度线程太久
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("🔌 Reconnect attempt interrupted: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("🔌 Failed to reconnect: " + e.getMessage());
                    // 如果 reconnectBlocking() 抛出异常，确保 onClose 会被再次调用以触发下一次重连
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            System.out.println("🔌 Reached max reconnect attempts (" + MAX_RECONNECT_ATTEMPTS + "). Giving up.");
            shutdownScheduler();
        }
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("❌ WebSocket error: " + ex.getMessage());
        ex.printStackTrace();
        // onError 之后通常会调用 onClose，所以重连逻辑主要放在 onClose 中处理
    }

    /**
     * 主动关闭连接并停止重连尝试。
     */
    public void closeConnection() {
        System.out.println("🔌 Intentionally closing connection...");
        intentionalClose = true;
        super.close();
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
            System.out.println("🔌 Reconnect scheduler shut down.");
        }
    }

    // 示例：如何启动客户端
    public static void main(String[] args) {
        TaskWebSocketClient client = new TaskWebSocketClient();
        try {
            System.out.println("🚀 Attempting to connect to WebSocket server...");
            client.connectBlocking(); // 初始连接尝试
        } catch (InterruptedException e) {
            System.err.println("Initial connection interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
            client.closeConnection(); // 清理
        } catch (Exception e) {
            System.err.println("Initial connection failed: " + e.getMessage());
            // 初始连接失败，onClose 应该会被调用，从而触发重连逻辑（如果配置了）
            // 如果 connectBlocking 抛出异常且没有调用 onClose，则需要手动处理或确保 onClose 被触发
        }

        // 保持主线程运行，以便客户端可以持续运行和重连
        // 在实际应用中，你可能会有其他逻辑来保持应用存活
        Runtime.getRuntime().addShutdownHook(new Thread(client::closeConnection));

        // 模拟长时间运行
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Main thread interrupted.");
            client.closeConnection();
        }
    }
}