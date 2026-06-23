package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.embedded.AdapterResultFrame;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket-local diagnostics for inbound result frames.
 */
public final class WebSocketResultDiagnosticsProvider {

    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";

    private final String adapterId;
    private final TransportJsonFrameParser parser;

    public WebSocketResultDiagnosticsProvider(String adapterId, TransportJsonFrameParser parser) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public Map<String, String> diagnostics(JsonObject frame, AdapterResultFrame result) {
        Objects.requireNonNull(result, "result");
        String routeKey = WebSocketStringValues.firstNonBlank(parser.readString(frame, ROUTE_KEY_FIELD));
        String traceId = WebSocketStringValues.firstNonBlank(
                parser.readString(frame, TRACE_ID_FIELD),
                result.traceSeed(),
                result.frameId(),
                result.correlationRef()
        );
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("adapterId", adapterId);
        if (routeKey != null) {
            values.put("routeKey", routeKey);
        }
        if (traceId != null) {
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
