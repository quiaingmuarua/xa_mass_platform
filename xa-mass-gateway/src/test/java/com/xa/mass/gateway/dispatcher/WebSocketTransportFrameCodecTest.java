package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.sdk.event.EventResponse;
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
    void controlFramesStayOutsideCanonicalTaskDispatchDetection() {
        assertTrue(!codec.isCanonicalTaskDispatch(controlRequestFrame("demoApp", "crawler.fetch-page")));
        assertTrue(!codec.isCanonicalTaskDispatch(controlResponseFrame("demoApp", "crawler.fetch-page")));
    }

    @Test
    void eventFirstControlRequestDecodesIntoCanonicalRequest() {
        JsonObject frame = controlRequestFrame("demoApp", "crawler.fetch-page");
        var request = codec.decodeControlEventRequest(frame);
        var principal = codec.decodeControlEventPrincipal(frame);
        EventResponse response = EventResponse.success(Map.of("echoEvent", request.getEvent().value()), request.getRequestId());
        JsonObject encodedResponse = codec.parseObject(codec.encodeControlEventResponse(frame, response));

        assertTrue(codec.isEventFirstControlRequest(frame));
        assertEquals("crawler.fetch-page", request.getEvent().value());
        assertEquals("req-bridge", request.getRequestId());
        assertEquals("https://example.test", request.getPayload().get("url"));
        assertEquals("client-a", principal.getClientId());
        assertEquals("user-a", principal.getUserId());
        assertTrue(encodedResponse.get(WorkerControlEventProtocol.SUCCESS_FIELD).getAsBoolean());
        assertEquals("req-bridge", encodedResponse.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
        assertEquals("crawler.fetch-page", encodedResponse.get(WorkerControlEventProtocol.EVENT_CODE_FIELD).getAsString());
    }

    @Test
    void controlEventResponseDetectionUsesRootEventCode() {
        JsonObject frame = controlResponseFrame("demoApp", "crawler.fetch-page");

        assertTrue(codec.isEventFirstControlResponse(frame));
        assertEquals("crawler.fetch-page", codec.extractEventCode(frame));
        assertEquals("msg-2", codec.extractMessageId(frame));
        assertEquals("worker-1", codec.extractWorkerId(frame));
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
        assertEquals("msg-1", report.getMsgId());
        assertTrue(report.isSuccess());
        assertEquals("completed", report.getDetail());
        assertEquals("SUCCESS", report.getOutput().get("status"));
    }

    @Test
    void legacyMsgIdOnlyFrameIsRejected() {
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

    private JsonObject controlRequestFrame(String project, String eventCode) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, "msg-1");
        frame.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, false);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, "worker-1");
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        frame.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-bridge");
        frame.add(WorkerControlEventProtocol.HEADERS_FIELD, payload("trace", "trace-1"));
        frame.add(WorkerControlEventProtocol.PAYLOAD_FIELD, payload("url", "https://example.test"));
        frame.add(WorkerControlEventProtocol.PRINCIPAL_FIELD, payload(
                WorkerControlEventProtocol.CLIENT_ID_FIELD, "client-a",
                WorkerControlEventProtocol.USER_ID_FIELD, "user-a"
        ));
        return frame;
    }

    private JsonObject controlResponseFrame(String project, String eventCode) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, "msg-2");
        frame.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, true);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, "worker-1");
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        frame.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-bridge");
        frame.addProperty(WorkerControlEventProtocol.SUCCESS_FIELD, true);
        frame.addProperty(WorkerControlEventProtocol.CODE_FIELD, "OK");
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_FIELD, "success");
        frame.add(WorkerControlEventProtocol.DATA_FIELD, payload("replyToMessageId", "msg-1"));
        return frame;
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
