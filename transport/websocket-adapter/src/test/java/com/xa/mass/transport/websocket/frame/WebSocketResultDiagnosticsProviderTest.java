package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.embedded.AdapterResultFrame;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebSocketResultDiagnosticsProviderTest {

    private final TransportJsonFrameParser parser = new TransportJsonFrameParser();

    @Test
    void includesAdapterRouteAndExplicitTrace() {
        WebSocketResultDiagnosticsProvider provider = new WebSocketResultDiagnosticsProvider("websocket", parser);
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
        WebSocketResultDiagnosticsProvider provider = new WebSocketResultDiagnosticsProvider("websocket", parser);

        Map<String, String> diagnostics = provider.diagnostics(
                new JsonObject(),
                new AdapterResultFrame("corr-1", "payload", null, "frame-1")
        );

        assertEquals("websocket", diagnostics.get("adapterId"));
        assertEquals("frame-1", diagnostics.get("traceId"));
        assertFalse(diagnostics.containsKey("routeKey"));
    }
}
