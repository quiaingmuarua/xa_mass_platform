package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameReadersTest {

    private final TransportJsonFrameParser parser = new TransportJsonFrameParser();
    private final WorkerChannelFrameJsonCodec channelFrameCodec = new WorkerChannelFrameJsonCodec();

    @Test
    void sessionReaderIgnoresRouteAddressWhenPresent() {
        WebSocketSessionOpenFrameReader reader = new WebSocketSessionOpenFrameReader(parser);

        WebSocketSessionIdentity identity = reader.readHandshake(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1"
        );

        assertTrue(identity.complete());
        assertEquals("bucket-1", identity.workerGroupId());
        assertEquals("worker-1", identity.workerId());
    }

    @Test
    void sessionReaderRequiresOnlyWorkerGroupAndWorkerId() {
        WebSocketSessionOpenFrameReader reader = new WebSocketSessionOpenFrameReader(parser);

        WebSocketSessionIdentity identity = reader.readHandshake(
                "/ws?workerId=worker-1&workerGroupId=bucket-1"
        );

        assertTrue(identity.complete());
        assertEquals("bucket-1", identity.workerGroupId());
        assertEquals("worker-1", identity.workerId());
    }

    @Test
    void resultReaderRecognizesOnlyResultFramesAndBuildsExplicitEntry() {
        WebSocketResultIngressFrameReader reader = new WebSocketResultIngressFrameReader("websocket", parser);
        JsonObject unsupported = parser.parseObject(channelFrameCodec.encodeGenerated(
                WorkerChannelFrame.ACTION,
                "{}"
        ));
        assertFalse(reader.isResultFrame(unsupported));

        JsonObject frame = replyFrame("corr-1", true, "ok");
        frame.addProperty("routeKey", "inline-route");

        assertTrue(reader.isResultFrame(frame));
        ResultIngressEntry entry = reader.toEntry(frame);

        assertEquals("corr-1", entry.partitionKey());
        assertEquals("corr-1", entry.message().resultCorrelationRef());
        assertEquals("websocket", entry.diagnostics().get("adapterId"));
        assertEquals("inline-route", entry.diagnostics().get("routeKey"));
        JsonObject payload = JsonParser.parseString(entry.message().payload()).getAsJsonObject();
        assertEquals("corr-1", payload.get("replyRef").getAsString());
        assertFalse(payload.has("taskId"));
        assertFalse(payload.has("messageId"));
        assertTrue(payload.get("success").getAsBoolean());

        JsonObject resultShellWithoutSuccess = replyFrame("corr-2", false, "failed");
        assertTrue(reader.isResultFrame(resultShellWithoutSuccess));
    }

    @Test
    void resultReaderRejectsLegacyResultCorrelationRefAlias() {
        WebSocketResultIngressFrameReader reader = new WebSocketResultIngressFrameReader("websocket", parser);
        JsonObject reply = new JsonObject();
        reply.addProperty("resultCorrelationRef", "corr-legacy");
        reply.addProperty("success", true);
        reply.addProperty("body", "ok");
        JsonObject frame = parser.parseObject(channelFrameCodec.encodeGenerated(
                WorkerChannelFrame.ACTION_REPLY,
                parser.toJson(reply)
        ));

        assertTrue(reader.isResultFrame(frame));
        assertNull(reader.replyRef(frame));
        assertThrows(IllegalArgumentException.class, () -> reader.toEntry(frame));
    }

    private JsonObject replyFrame(String replyRef, boolean success, String body) {
        JsonObject reply = new JsonObject();
        reply.addProperty("replyRef", replyRef);
        reply.addProperty("success", success);
        reply.addProperty("body", body);
        return parser.parseObject(channelFrameCodec.encodeGenerated(
                WorkerChannelFrame.ACTION_REPLY,
                parser.toJson(reply)
        ));
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
