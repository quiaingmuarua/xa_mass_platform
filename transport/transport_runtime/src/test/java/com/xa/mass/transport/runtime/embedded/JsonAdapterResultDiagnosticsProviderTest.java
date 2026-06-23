package com.xa.mass.transport.runtime.embedded;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonAdapterResultDiagnosticsProviderTest {

    private final TransportJsonFrameParser parser = new TransportJsonFrameParser();

    @Test
    void includesAdapterRouteAndExplicitTrace() {
        JsonAdapterResultDiagnosticsProvider provider =
                new JsonAdapterResultDiagnosticsProvider("websocket", parser);
        JsonObject frame = new JsonObject();
        frame.addProperty("routeKey", "route-1");
        frame.addProperty("traceId", "trace-explicit");

        Map<String, String> diagnostics = provider.diagnostics(
                frame,
                new AdapterResultFrame("corr-1", "payload", "trace-seed", "frame-1")
        );

        assertEquals("websocket", diagnostics.get("adapterId"));
        assertEquals("route-1", diagnostics.get("routeKey"));
        assertEquals("trace-explicit", diagnostics.get("traceId"));
    }

    @Test
    void fallsBackToFrameTraceFactsWithoutRoute() {
        JsonAdapterResultDiagnosticsProvider provider =
                new JsonAdapterResultDiagnosticsProvider("websocket", parser);

        Map<String, String> diagnostics = provider.diagnostics(
                new JsonObject(),
                new AdapterResultFrame("corr-1", "payload", null, "frame-1")
        );

        assertEquals("websocket", diagnostics.get("adapterId"));
        assertEquals("frame-1", diagnostics.get("traceId"));
        assertFalse(diagnostics.containsKey("routeKey"));
    }

    @Test
    void routeKeyHintSupportsAdapterSessionRouteEvidence() {
        JsonAdapterResultDiagnosticsProvider provider =
                new JsonAdapterResultDiagnosticsProvider("socket", parser);

        Map<String, String> diagnostics = provider.diagnostics(
                new JsonObject(),
                new AdapterResultFrame("corr-1", "payload", null, null),
                "socket-route-9"
        );

        assertEquals("socket", diagnostics.get("adapterId"));
        assertEquals("socket-route-9", diagnostics.get("routeKey"));
        assertEquals("corr-1", diagnostics.get("traceId"));
    }
}
