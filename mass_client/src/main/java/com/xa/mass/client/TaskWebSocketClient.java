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

public class TaskWebSocketClient extends WebSocketClient {

    private final Gson gson = new Gson();

    public TaskWebSocketClient() {
        super(URI.create("ws://localhost:8088/ws"));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("✅ Connected to server");

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
        System.out.println("🔌 Disconnected: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace();
    }
}
