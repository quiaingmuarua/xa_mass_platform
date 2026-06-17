package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessage;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds opaque result ingress envelopes from WebSocket worker result frames.
 */
public final class WebSocketResultIngressFrameReader {

    private static final String RESULT_CORRELATION_REF_FIELD = "resultCorrelationRef";
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final String SUCCESS_FIELD = "success";

    private final String adapterId;
    private final WebSocketJsonFrameParser parser;

    public WebSocketResultIngressFrameReader(String adapterId, WebSocketJsonFrameParser parser) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.parser = parser;
    }

    public boolean isResultFrame(JsonObject frame) {
        return frame != null
                && parser.readString(frame, EVENT_CODE_FIELD) == null
                && parser.readString(frame, RESULT_CORRELATION_REF_FIELD) != null
                && parser.readBoolean(frame, SUCCESS_FIELD) != null;
    }

    public TransportResultIngressEnvelope toEnvelope(JsonObject frame, WebSocketInboundMessage inboundMessage) {
        String resultCorrelationRef = parser.readString(frame, RESULT_CORRELATION_REF_FIELD);
        if (resultCorrelationRef == null) {
            throw new IllegalArgumentException(RESULT_CORRELATION_REF_FIELD + " is required");
        }
        if (parser.readBoolean(frame, SUCCESS_FIELD) == null) {
            throw new IllegalArgumentException(SUCCESS_FIELD + " is required");
        }
        String routeKey = WebSocketStringValues.firstNonBlank(
                parser.readString(frame, ROUTE_KEY_FIELD),
                inboundMessage != null ? inboundMessage.getRouteKey() : null
        );
        String traceId = WebSocketStringValues.firstNonBlank(
                parser.readString(frame, TRACE_ID_FIELD),
                resultCorrelationRef
        );
        return TransportResultIngressEnvelope.received(
                parser.toJson(frame),
                null,
                resultCorrelationRef,
                diagnostics(routeKey, traceId)
        );
    }

    public String resultCorrelationRef(JsonObject frame) {
        return parser.readString(frame, RESULT_CORRELATION_REF_FIELD);
    }

    public String traceId(JsonObject frame) {
        return parser.readString(frame, TRACE_ID_FIELD);
    }

    public String eventCode(JsonObject frame) {
        return parser.readString(frame, EVENT_CODE_FIELD);
    }

    public String project(JsonObject frame) {
        return parser.readString(frame, "project");
    }

    private Map<String, String> diagnostics(String routeKey, String traceId) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("adapterId", adapterId);
        if (routeKey != null && !routeKey.isBlank()) {
            values.put("routeKey", routeKey);
        }
        if (traceId != null && !traceId.isBlank()) {
            values.put("traceId", traceId);
        }
        return Map.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
