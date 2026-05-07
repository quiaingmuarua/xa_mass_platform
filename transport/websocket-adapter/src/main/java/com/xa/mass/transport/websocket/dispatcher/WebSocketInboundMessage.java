package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;

/**
 * Adapter-internal inbound WebSocket frame with connection metadata captured by
 * the server layer.
 */
public final class WebSocketInboundMessage {

    private final String rawJson;
    private final String workerId;
    private final String routeKey;
    private final String endpointId;
    private final JsonObject parsedFrame;

    public WebSocketInboundMessage(String rawJson,
                                   String workerId,
                                   String routeKey,
                                   String endpointId,
                                   JsonObject parsedFrame) {
        this.rawJson = rawJson;
        this.workerId = normalize(workerId);
        this.routeKey = normalize(routeKey);
        this.endpointId = normalize(endpointId);
        this.parsedFrame = parsedFrame;
    }

    public static WebSocketInboundMessage raw(String rawJson) {
        return new WebSocketInboundMessage(rawJson, null, null, null, null);
    }

    public static WebSocketInboundMessage of(String rawJson, String workerId, String routeKey, String endpointId) {
        return new WebSocketInboundMessage(rawJson, workerId, routeKey, endpointId, null);
    }

    public static WebSocketInboundMessage of(String rawJson,
                                             String workerId,
                                             String routeKey,
                                             String endpointId,
                                             JsonObject parsedFrame) {
        return new WebSocketInboundMessage(rawJson, workerId, routeKey, endpointId, parsedFrame);
    }

    public String getRawJson() {
        return rawJson;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public JsonObject getParsedFrame() {
        return parsedFrame;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
