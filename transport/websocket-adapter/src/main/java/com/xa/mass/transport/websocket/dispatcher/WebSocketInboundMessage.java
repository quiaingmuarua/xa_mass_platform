package com.xa.mass.transport.websocket.dispatcher;

/**
 * Adapter-internal inbound WebSocket frame with connection metadata captured by
 * the server layer.
 */
public final class WebSocketInboundMessage {

    private final String rawJson;
    private final String workerId;
    private final String endpointId;

    public WebSocketInboundMessage(String rawJson, String workerId, String endpointId) {
        this.rawJson = rawJson;
        this.workerId = normalize(workerId);
        this.endpointId = normalize(endpointId);
    }

    public static WebSocketInboundMessage raw(String rawJson) {
        return new WebSocketInboundMessage(rawJson, null, null);
    }

    public static WebSocketInboundMessage of(String rawJson, String workerId, String endpointId) {
        return new WebSocketInboundMessage(rawJson, workerId, endpointId);
    }

    public String getRawJson() {
        return rawJson;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
