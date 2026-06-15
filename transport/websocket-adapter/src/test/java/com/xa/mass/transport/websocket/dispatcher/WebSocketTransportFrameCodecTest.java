package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.AdapterEndpoint;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTransportFrameCodecTest {

    private static final Gson GSON = new Gson();

    private WebSocketTransportFrameCodec codec;

    @BeforeEach
    void setUp() {
        codec = new WebSocketTransportFrameCodec();
    }

    @Test
    void encodesCanonicalTaskDispatch() {
        AdapterDispatchRequest request = new AdapterDispatchRequest(
                "delivery-1",
                "websocket",
                "worker-1",
                new TaskDispatchContent(
                        "task-1",
                        "msg-1",
                        "crawler.fetch-page",
                        Map.of("target", "https://example.test"),
                        Map.of("textContent", "hello")
                ),
                new TaskDispatchExecutionContext(
                        "attempt-1",
                        1,
                        2,
                        "batch-1"
                ),
                new AdapterEndpoint("route-1", "node-1", "conn-1", 10_000L),
                1L
        );

        JsonObject frame = codec.parseObject(codec.encodeCanonicalTaskDispatch(request));

        assertNotNull(frame);
        assertEquals("msg-1", frame.get("messageId").getAsString());
        assertEquals("worker-1", frame.get(TransportPacket.PAYLOAD_WORKER_ID).getAsString());
        assertEquals("task-1", frame.get("taskId").getAsString());
        assertEquals("crawler.fetch-page", frame.get("eventCode").getAsString());
        assertEquals("https://example.test",
                frame.getAsJsonObject(TransportPacket.PAYLOAD_INPUT).get("target").getAsString());
        assertFalse(frame.has(TransportPacket.PAYLOAD_PROJECT));
        assertFalse(frame.has(TransportPacket.PAYLOAD_TASK_NAME));
        assertFalse(frame.has(TransportPacket.PAYLOAD_USER_ID));
    }

    @Test
    void framesWithEventCodeAndSuccessAreNotTreatedAsCanonicalTaskResults() {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty(TransportPacket.PAYLOAD_WORKER_ID, "worker-1");
        frame.addProperty("taskId", "task-1");
        frame.addProperty("eventCode", "mock.state.get");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);

        assertFalse(codec.isCanonicalTaskResult(frame));
    }

    @Test
    void canonicalTaskResultDecodesIntoTaskResultReport() {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty(TransportPacket.PAYLOAD_WORKER_ID, "worker-1");
        frame.addProperty(TransportPacket.PAYLOAD_PROJECT, "demoApp");
        frame.addProperty("taskId", "task-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "completed");
        frame.add(TransportPacket.PAYLOAD_OUTPUT, payload("status", "SUCCESS", "mockData", "completed"));

        assertTrue(codec.isCanonicalTaskResult(frame));
        var report = codec.decodeCanonicalTaskResult(frame);
        assertEquals("task-1", report.getTaskId());
        assertEquals("msg-1", report.getMessageId());
        assertTrue(report.isSuccess());
        assertEquals("completed", report.getDetail());
        assertEquals("SUCCESS", report.getOutput().get("status"));
    }

    @Test
    void routeKeyCanBeExtractedIndependentlyFromWorkerId() {
        JsonObject frame = new JsonObject();
        frame.addProperty(TransportPacket.PAYLOAD_WORKER_ID, "worker-1");
        frame.addProperty("routeKey", "ws-route-7");

        assertEquals("worker-1", codec.extractWorkerId(frame));
        assertEquals("ws-route-7", codec.extractRouteKey(frame));
    }

    private JsonObject payload(Object... keyValues) {
        JsonObject payload = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value == null) {
                payload.add(key, null);
            } else if (value instanceof String str) {
                payload.addProperty(key, str);
            } else if (value instanceof Boolean bool) {
                payload.addProperty(key, bool);
            } else if (value instanceof Number number) {
                payload.addProperty(key, number);
            } else if (value instanceof JsonObject object) {
                payload.add(key, object);
            } else {
                payload.add(key, GSON.toJsonTree(value));
            }
        }
        return payload;
    }
}
