package com.xa.mass.client;

import com.google.gson.Gson;
import com.xa.mass.model.message.TaskMessage;
import com.xa.mass.model.message.TaskResult;
import com.xa.mass.model.message.MsgType;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class TaskWebSocketClient extends WebSocketClient {

    private final Gson gson = new Gson();

    public TaskWebSocketClient() {
        super(URI.create("ws://localhost:8080/ws"));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("Connected to server");
    }

    @Override
    public void onMessage(String message) {
        System.out.println("Received: " + message);
        try {
            TaskMessage taskMsg = gson.fromJson(message, TaskMessage.class);
            TaskResult response = buildResponse(taskMsg);
            send(gson.toJson(response));
        } catch (Exception e) {
            System.err.println("Failed to parse message or send response: " + e.getMessage());
        }
    }

    private TaskResult buildResponse(TaskMessage msg) {
        TaskResult result = new TaskResult();
        result.setMsgId(msg.getMsgId());
        result.setMsgType(MsgType.STEP); // 可根据实际任务设置为 STEP 或 ALL
        result.setCode("200");
        result.setSubCode("MOCK_SUCCESS");
        result.setMessage("Mock execution successful");

        Map<String, Object> res = new HashMap<>();
        res.put("stepId", msg.getSteps() != null && !msg.getSteps().isEmpty() ? msg.getSteps().get(0).getStepId() : "step-0");
        res.put("mockData", "Executed by mock client");
        result.setResult(res);

        return result;
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("Disconnected: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace();
    }
}