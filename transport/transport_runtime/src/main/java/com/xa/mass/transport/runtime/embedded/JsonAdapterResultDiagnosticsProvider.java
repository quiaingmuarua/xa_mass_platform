package com.xa.mass.transport.runtime.embedded;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default diagnostics projection for JSON worker-channel result frames.
 */
public final class JsonAdapterResultDiagnosticsProvider implements AdapterResultDiagnosticsProvider<JsonObject> {

    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";

    private final String adapterId;
    private final TransportJsonFrameParser parser;

    public JsonAdapterResultDiagnosticsProvider(String adapterId, TransportJsonFrameParser parser) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public Map<String, String> diagnostics(JsonObject frame, AdapterResultFrame result) {
        return diagnostics(frame, result, null);
    }

    public Map<String, String> diagnostics(JsonObject frame,
                                           AdapterResultFrame result,
                                           String routeKeyHint) {
        Objects.requireNonNull(result, "result");
        String routeKey = firstNonBlank(
                routeKeyHint,
                parser.readString(frame, ROUTE_KEY_FIELD)
        );
        String traceId = firstNonBlank(
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
