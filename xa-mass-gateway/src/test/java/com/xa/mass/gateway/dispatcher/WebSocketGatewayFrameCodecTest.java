package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec;
import com.xa.mass.sdk.event.EventResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketGatewayFrameCodecTest {

    private static final Gson GSON = new Gson();

    private WebSocketGatewayFrameCodec codec;

    @BeforeEach
    void setUp() {
        codec = new WebSocketGatewayFrameCodec();
    }

    @Test
    void detectsTaskStepTransportShell() {
        assertTrue(codec.isTaskStep(taskFrame("TASK", "step", false, "demoApp", null)));
    }

    @Test
    void controlFramesStayOutsideTaskShellDetection() {
        assertTrue(!codec.isTaskStep(controlRequestFrame("demoApp", "crawler.fetch-page")));
        assertTrue(!codec.isTaskStep(controlResponseFrame("demoApp", "crawler.fetch-page")));
        assertTrue(!codec.isHeartbeatPing(controlRequestFrame("demoApp", "crawler.fetch-page")));
        assertTrue(!codec.isHeartbeatPong(controlResponseFrame("demoApp", "crawler.fetch-page")));
    }

    @Test
    void heartbeatPingEncodesPongJson() {
        JsonObject ping = taskFrame("PING", "heartbeat", false, null, null);

        assertTrue(codec.isHeartbeatPing(ping));

        JsonObject pong = codec.parseObject(codec.encodeHeartbeatPong(ping));
        assertNotNull(pong);
        assertEquals("PONG", pong.get("msgType").getAsString());
        assertEquals("heartbeat", pong.get("subMsgType").getAsString());
        assertTrue(pong.get("response").getAsBoolean());
        assertEquals("worker-1", pong.getAsJsonObject("context").get("workerId").getAsString());
    }

    @Test
    void heartbeatPongDetectionUsesTransportShellOnly() {
        JsonObject pong = taskFrame("PONG", "heartbeat", false, null, null);

        assertTrue(codec.isHeartbeatPong(pong));
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
    void taskAckDoesNotBackfillEventCode() {
        JsonObject request = taskFrame("TASK", "step", false, "demoApp", null);
        JsonObject ack = codec.parseObject(codec.encodeTaskAck(request, 200, "ok"));

        assertNull(codec.extractEventCode(ack));
    }

    private JsonObject taskFrame(String msgType, String subMsgType, boolean response, String project, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", "msg-1");
        frame.addProperty("msgType", msgType);
        frame.addProperty("subMsgType", subMsgType);
        frame.addProperty("response", response);
        frame.addProperty("from", "CLIENT");
        if (project != null) {
            frame.addProperty("project", project);
        }
        JsonObject context = new JsonObject();
        context.addProperty("workerId", "worker-1");
        frame.add("context", context);
        frame.add("payload", payload != null ? payload : new JsonObject());
        return frame;
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
