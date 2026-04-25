package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        com.xa.mass.transport.model.TaskDispatchItem item = new com.xa.mass.transport.model.TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "user-a",
                2,
                "worker-1",
                "worker-context-1",
                "batch-1",
                Map.of("target", "https://example.test"),
                Map.of("textContent", "hello")
        );

        JsonObject frame = codec.parseObject(codec.encodeCanonicalTaskDispatch(item));

        assertNotNull(frame);
        assertTrue(codec.isCanonicalTaskDispatch(frame));
        assertEquals("msg-1", frame.get("messageId").getAsString());
        assertEquals("worker-1", frame.get("workerId").getAsString());
        assertEquals("task-1", frame.get("taskId").getAsString());
        assertEquals("crawler.fetch-page", frame.get("eventCode").getAsString());
        assertEquals("https://example.test", frame.getAsJsonObject("input").get("target").getAsString());
    }

    @Test
    void framesWithEventCodeAndSuccessAreNotTreatedAsCanonicalTaskResults() {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("workerId", "worker-1");
        frame.addProperty("taskId", "task-1");
        frame.addProperty("eventCode", "mock.state.get");
        frame.addProperty("success", true);

        assertFalse(codec.isCanonicalTaskResult(frame));
    }

    @Test
    void canonicalTaskResultDecodesIntoTaskResultReport() {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("workerId", "worker-1");
        frame.addProperty("project", "demoApp");
        frame.addProperty("taskId", "task-1");
        frame.addProperty("success", true);
        frame.addProperty("detail", "completed");
        frame.add("output", payload("status", "SUCCESS", "mockData", "completed"));

        assertTrue(codec.isCanonicalTaskResult(frame));
        var report = codec.decodeCanonicalTaskResult(frame);
        assertEquals("task-1", report.getTaskId());
        assertEquals("msg-1", report.getMessageId());
        assertTrue(report.isSuccess());
        assertEquals("completed", report.getDetail());
        assertEquals("SUCCESS", report.getOutput().get("status"));
    }

    @Test
    void msgIdOnlyFrameIsRejected() {
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", "legacy-1");
        frame.addProperty("workerId", "worker-1");
        frame.addProperty("taskId", "task-1");

        assertNull(codec.extractMessageId(frame));
        assertFalse(codec.isCanonicalTaskDispatch(frame));
    }

    @Test
    void legacyContextTaskRoutingIsRejected() {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("workerId", "worker-1");
        JsonObject context = new JsonObject();
        context.addProperty("taskId", "task-1");
        frame.add("context", context);

        assertFalse(codec.isCanonicalTaskDispatch(frame));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeCanonicalTaskResult(frame));
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
