package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketResultIngressFrameReader;
import com.xa.mass.transport.websocket.frame.WebSocketWorkerChannelFrameCodec;
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
    private WebSocketWorkerChannelFrameCodec channelFrameCodec;
    private WebSocketResultIngressFrameReader resultFrameReader;
    private WebSocketDispatcherContext context;
    private RawWorkerRouteEndpointRegistry rawRouteEndpointRegistry;
    private WebSocketInputProcessor inputProcessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        frameParser = new WebSocketJsonFrameParser();
        channelFrameCodec = new WebSocketWorkerChannelFrameCodec();
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
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        context = createContext(entry -> {
            capturedEntry.set(entry);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        boolean result = inputProcessor.process(canonicalTaskResultFrame("corr-1", true, "ok"));

        assertTrue(result);
        assertNotNull(capturedEntry.get());
        assertEquals("websocket", capturedEntry.get().diagnostics().get("adapterId"));
        assertEquals("route-1", capturedEntry.get().diagnostics().get("routeKey"));
        assertEquals("corr-1", capturedEntry.get().partitionKey());
        assertEquals("corr-1", capturedEntry.get().message().resultCorrelationRef());
        assertPayload(capturedEntry.get(), "corr-1", true, "ok");
    }

    @Test
    void canonicalTaskResultDoesNotUseSessionRouteMetadata() {
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        context = createContext(entry -> {
            capturedEntry.set(entry);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = canonicalTaskResultFrameObject("corr-1", true, "ok", null);

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-from-handshake",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNotNull(capturedEntry.get());
        assertNull(capturedEntry.get().diagnostics().get("routeKey"));
        assertEquals("corr-1", capturedEntry.get().message().resultCorrelationRef());
        assertPayload(capturedEntry.get(), "corr-1", true, "ok");
    }

    @Test
    void canonicalTaskResultCanReuseParsedInboundFrame() {
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        context = createContext(entry -> {
            capturedEntry.set(entry);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = canonicalTaskResultFrameObject("corr-1", true, "ok", null);

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                "not-json-but-already-parsed",
                "worker-from-session",
                "endpoint-1",
                frame
        ));

        assertTrue(result);
        assertNotNull(capturedEntry.get());
        assertNull(capturedEntry.get().diagnostics().get("routeKey"));
        assertEquals("corr-1", capturedEntry.get().message().resultCorrelationRef());
        assertPayload(capturedEntry.get(), "corr-1", true, "ok");
    }

    @Test
    void canonicalTaskResultPrefersInlineRouteKeyOverSessionMetadata() {
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        context = createContext(entry -> {
            capturedEntry.set(entry);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject frame = canonicalTaskResultFrameObject("corr-1", true, "ok", "inline-route");

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-from-session",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNotNull(capturedEntry.get());
        assertEquals("inline-route", capturedEntry.get().diagnostics().get("routeKey"));
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
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        context = createContext(entry -> {
            capturedEntry.set(entry);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("body", "ok");
        JsonObject frame = frameParser.parseObject(channelFrameCodec.frame(
                WebSocketWorkerChannelFrameCodec.ACTION_REPLY,
                frameParser.toJson(reply)
        ));

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-1",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNull(capturedEntry.get());
    }

    @Test
    void canonicalTaskResultWithLegacyCorrelationAliasIsRejectedWithoutIngest() {
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        context = createContext(entry -> {
            capturedEntry.set(entry);
            return true;
        });
        inputProcessor = new WebSocketInputProcessor(context);

        JsonObject reply = new JsonObject();
        reply.addProperty("resultCorrelationRef", "corr-legacy");
        reply.addProperty("success", true);
        reply.addProperty("body", "ok");
        JsonObject frame = frameParser.parseObject(channelFrameCodec.frame(
                WebSocketWorkerChannelFrameCodec.ACTION_REPLY,
                frameParser.toJson(reply)
        ));

        boolean result = inputProcessor.process(WebSocketInboundMessage.of(
                frameParser.toJson(frame),
                "worker-1",
                "endpoint-1"
        ));

        assertTrue(result);
        assertNull(capturedEntry.get());
    }

    private WebSocketDispatcherContext createContext(AdapterResultIngressSink taskResultIngestChannel) {
        return new WebSocketDispatcherContext(
                "websocket",
                rawRouteEndpointRegistry,
                frameParser,
                resultFrameReader,
                taskResultIngestChannel
        );
    }

    private void assertPayload(ResultIngressEntry entry,
                               String resultCorrelationRef,
                               boolean success,
                               String detail) {
        JsonObject payload = JsonParser.parseString(entry.message().payload()).getAsJsonObject();
        assertEquals(resultCorrelationRef, payload.get("replyRef").getAsString());
        assertFalse(payload.has("taskId"));
        assertFalse(payload.has("messageId"));
        assertEquals(success, payload.get("success").getAsBoolean());
        assertEquals(detail, payload.get("body").getAsString());
    }

    private String canonicalTaskResultFrame(String resultCorrelationRef, boolean success, String detail) {
        return frameParser.toJson(canonicalTaskResultFrameObject(resultCorrelationRef, success, detail, "route-1"));
    }

    private JsonObject canonicalTaskResultFrameObject(String resultCorrelationRef,
                                                      boolean success,
                                                      String detail,
                                                      String routeKey) {
        JsonObject reply = new JsonObject();
        reply.addProperty("replyRef", resultCorrelationRef);
        reply.addProperty("success", success);
        reply.addProperty("body", detail);
        JsonObject frame = new JsonObject();
        frame.addProperty("frameId", "frame-" + resultCorrelationRef);
        frame.addProperty("kind", WebSocketWorkerChannelFrameCodec.ACTION_REPLY);
        frame.addProperty("body", frameParser.toJson(reply));
        if (routeKey != null) {
            frame.addProperty("routeKey", routeKey);
        }
        return frame;
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
