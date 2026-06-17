package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameReadersTest {

    private final WebSocketJsonFrameParser parser = new WebSocketJsonFrameParser();

    @Test
    void jsonParserParsesObjectsAndRejectsMalformedFrames() {
        JsonObject parsed = parser.parseObject("{\"messageId\":\"msg-1\"}");

        assertNotNull(parsed);
        assertEquals("msg-1", parser.readString(parsed, "messageId"));
        assertNull(parser.parseObject("{\"messageId\":\"broken\""));
        assertNull(parser.parseObject("[\"not-object\"]"));
    }

    @Test
    void sessionReaderUsesExplicitRouteAddressWhenPresent() {
        WebSocketSessionOpenFrameReader reader = new WebSocketSessionOpenFrameReader(parser);

        WebSocketSessionIdentity identity = reader.readHandshake(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1"
        );

        assertTrue(identity.complete());
        assertEquals("bucket-1", identity.workerGroupId());
        assertEquals("worker-1", identity.workerId());
        assertEquals("ws-route-1", identity.endpointAddress());
    }

    @Test
    void sessionReaderGeneratesInternalEndpointAddressWhenRouteAddressIsOmitted() {
        WebSocketSessionOpenFrameReader reader = new WebSocketSessionOpenFrameReader(parser);

        WebSocketSessionIdentity identity = reader.readHandshake(
                "/ws?workerId=worker-1&workerGroupId=bucket-1"
        );

        assertTrue(identity.complete());
        assertEquals("bucket:bucket-1", identity.endpointAddress());
    }

    @Test
    void resultReaderRecognizesOnlyResultFramesAndBuildsOpaqueEnvelope() {
        WebSocketResultIngressFrameReader reader = new WebSocketResultIngressFrameReader("websocket", parser);
        JsonObject unsupported = new JsonObject();
        unsupported.addProperty("resultCorrelationRef", "corr-1");
        unsupported.addProperty("eventCode", "mock.state.get");
        unsupported.addProperty("success", true);
        assertFalse(reader.isResultFrame(unsupported));

        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", "corr-1");
        frame.addProperty("routeKey", "inline-route");
        frame.addProperty("success", true);
        frame.addProperty("detail", "ok");
        frame.add("output", payload("status", "SUCCESS"));

        assertTrue(reader.isResultFrame(frame));
        TransportResultIngressEnvelope envelope = reader.toEnvelope(
                frame,
                WebSocketInboundMessage.of(parser.toJson(frame), "worker-1", "session-route", "endpoint-1")
        );

        assertEquals("websocket", envelope.diagnostic("adapterId"));
        assertEquals("inline-route", envelope.diagnostic("routeKey"));
        assertEquals("corr-1", envelope.getPartitionKey());
        JsonObject payload = JsonParser.parseString(envelope.getPayload()).getAsJsonObject();
        assertEquals("corr-1", payload.get("resultCorrelationRef").getAsString());
        assertFalse(payload.has("taskId"));
        assertFalse(payload.has("messageId"));
        assertTrue(payload.get("success").getAsBoolean());
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
