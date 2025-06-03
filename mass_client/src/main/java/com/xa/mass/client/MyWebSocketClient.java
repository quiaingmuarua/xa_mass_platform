package com.xa.mass.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class MyWebSocketClient extends WebSocketClient {

    public MyWebSocketClient() {
        super(URI.create("ws://localhost:8080/ws"));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("Connected to server");
    }

    @Override
    public void onMessage(String message) {
        System.out.println("Received: " + message);

        // 假设消息中有 msg_id，回传响应
        String response = buildResponse(message);
        send(response);
    }

    private String buildResponse(String msg) {
        // mock response
//        String msgId = extractMsgId(msg); // 自行解析 JSON
        return "{\"msg_id\":\"" + "6666" + "\",\"status\":\"success\"}";
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