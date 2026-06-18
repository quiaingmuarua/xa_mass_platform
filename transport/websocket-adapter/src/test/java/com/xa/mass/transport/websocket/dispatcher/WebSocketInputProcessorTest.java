package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketResultIngressFrameReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketInputProcessorTest {

    private WebSocketJsonFrameParser frameParser;
    private WebSocketResultIngressFrameReader resultFrameReader;
    private WebSocketDispatcherContext context;
    private RawWorkerRouteEndpointRegistry rawRouteEndpointRegistry;
    private WebSocketInputProcessor inputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        frameParser = new WebSocketJsonFrameParser();
        resultFrameReader = new WebSocketResultIngressFrameReader("websocket", frameParser);
        rawRouteEndpointRegistry = mock(RawWorkerRouteEndpointRegistry.class);
        context = createContext(null);
        inputProcessor = new WebSocketInputProcessor(context);
    }

    @Test
    void nullDecodeSkipsHandlingAndReturnsTrue() {
        boolean result = inputProcessor.process("not-valid-json-at-all-{{{}}}");
        assertTrue(result);
    }

    @Test
    void unsupportedFrameShapeIsIgnoredWithoutOutput() {
        JsonObject unsupportedFrame = new JsonObject();
        unsupportedFrame.addProperty("messageId", "msg-1");
        unsupportedFrame.addProperty("workerId", "worker-1");
        unsupportedFrame.addProperty("project", "proj");
        unsupportedFrame.addProperty("eventCode", "mock.state.get");

        boolean result = inputProcessor.process(frameParser.toJson(unsupportedFrame));

        assertTrue(result);
    }

    @Test
    void canonicalTaskResultIngestsWithoutOutput() {
        AtomicReference<TransportResultIngressEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(envelope -> {
            capturedEnvelope.set(envelope);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        boolean result = inputProcessor.process(canonicalTaskResultFrame("corr-1", true, "ok"));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertEquals("websocket", capturedEnvelope.get().diagnostic("adapterId"));
        assertEquals("route-1", capturedEnvelope.get().diagnostic("routeKey"));
        assertEquals("corr-1", capturedEnvelope.get().getPartitionKey());
        assertPayload(capturedEnvelope.get(), "corr-1", true, "ok");
    }

    @Test
    void canonicalTaskResultDoesNotUseSessionRouteMetadata() {
        AtomicReference<TransportResultIngressEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(envelope -> {
            capturedEnvelope.set(envelope);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", "corr-1");
        frame.addProperty("success", true);
        frame.addProperty("detail", "ok");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-from-handshake",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertNull(capturedEnvelope.get().diagnostic("routeKey"));
        assertEquals("corr-1", capturedEnvelope.get().getPartitionKey());
        assertPayload(capturedEnvelope.get(), "corr-1", true, "ok");
    }

    @Test
    void canonicalTaskResultCanReuseParsedInboundFrame() {
        AtomicReference<TransportResultIngressEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(envelope -> {
            capturedEnvelope.set(envelope);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", "corr-1");
        frame.addProperty("success", true);
        frame.addProperty("detail", "ok");
        frame.add("output", payload("status", "SUCCESS"));

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                "not-json-but-already-parsed",
                "worker-from-session",
                "endpoint-1",
                frame
        ));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertNull(capturedEnvelope.get().diagnostic("routeKey"));
        assertEquals("corr-1", capturedEnvelope.get().getPartitionKey());
        assertPayload(capturedEnvelope.get(), "corr-1", true, "ok");
    }

    @Test
    void canonicalTaskResultPrefersInlineRouteKeyOverSessionMetadata() {
        AtomicReference<TransportResultIngressEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(envelope -> {
            capturedEnvelope.set(envelope);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", "corr-1");
        frame.addProperty("success", true);
        frame.addProperty("routeKey", "inline-route");
        frame.addProperty("detail", "ok");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-from-session",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNotNull(capturedEnvelope.get());
        assertEquals("inline-route", capturedEnvelope.get().diagnostic("routeKey"));
    }

    @Test
    void canonicalTaskResultReturnsFalseWhenIngestChannelRejectsEnvelope() {
        context = createContext(envelope -> false);
        inputProcessor = new WebSocketInputProcessor(context);

        boolean result = inputProcessor.process(canonicalTaskResultFrame("corr-1", true, "ok"));

        assertFalse(result);
    }

    @Test
    void canonicalTaskResultWithoutCorrelationRefIsRejectedWithoutIngest() {
        AtomicReference<TransportResultIngressEnvelope> capturedEnvelope = new AtomicReference<>();
        context = createContext(envelope -> {
            capturedEnvelope.set(envelope);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = new JsonObject();
        frame.addProperty("success", true);
        frame.addProperty("detail", "ok");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-1",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNull(capturedEnvelope.get());
    }

    private WebSocketDispatcherContext createContext(TransportResultIngressChannel taskResultIngestChannel) {
        return new WebSocketDispatcherContext(
                "websocket",
                rawRouteEndpointRegistry,
                frameParser,
                resultFrameReader,
                taskResultIngestChannel
        );
    }

    private void assertPayload(TransportResultIngressEnvelope envelope,
                               String resultCorrelationRef,
                               boolean success,
                               String detail) {
        JsonObject payload = JsonParser.parseString(envelope.getPayload()).getAsJsonObject();
        assertEquals(resultCorrelationRef, payload.get("resultCorrelationRef").getAsString());
        assertFalse(payload.has("taskId"));
        assertFalse(payload.has("messageId"));
        assertEquals(success, payload.get("success").getAsBoolean());
        assertEquals(detail, payload.get("detail").getAsString());
    }

    private String canonicalTaskResultFrame(String resultCorrelationRef, boolean success, String detail) {
        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", resultCorrelationRef);
        frame.addProperty("routeKey", "route-1");
        frame.addProperty("success", success);
        frame.addProperty("detail", detail);
        frame.add("output",
                payload("status", success ? "SUCCESS" : "FAILED", "mockData", detail));
        return frameParser.toJson(frame);
    }

    private JsonObject payload(Object... keyValues) {
        JsonObject payload = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof String str) {
                payload.addProperty(key, str);
            } else if (value instanceof Boolean bool) {
                payload.addProperty(key, bool);
            } else if (value instanceof Number number) {
                payload.addProperty(key, number);
            } else if (value instanceof JsonObject object) {
                payload.add(key, object);
            }
        }
        return payload;
    }
}
