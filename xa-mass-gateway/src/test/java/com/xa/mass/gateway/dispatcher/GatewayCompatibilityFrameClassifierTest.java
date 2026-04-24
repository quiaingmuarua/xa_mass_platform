package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.sdk.event.EventResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayCompatibilityFrameClassifierTest {

    private static final Gson GSON = new Gson();

    private GsonMessageCodec codec;
    private GatewayCompatibilityFrameClassifier classifier;

    @BeforeEach
    void setUp() {
        codec = new GsonMessageCodec();
        classifier = new GatewayCompatibilityFrameClassifier(codec);
    }

    @Test
    void classifyTaskStepFrame() {
        GatewayCompatibilityFrameKind result = classifier.classify(frame("TASK", "step", false, "demoApp", null));
        assertEquals(GatewayCompatibilityFrameKind.TASK_STEP, result);
    }

    @Test
    void classifyWorkerControlEventResponseFrame() {
        JsonObject response = frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, true, "demoApp", payload(
                WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page"
        ));

        GatewayCompatibilityFrameKind result = classifier.classify(response);
        assertEquals(GatewayCompatibilityFrameKind.CONTROL_EVENT_RESPONSE, result);
    }

    @Test
    void defaultNoHandlerResultIsUnknown() {
        GatewayCompatibilityFrameKind result = classifier.classify(frame("CONTROL", "unknownSub", false, "demoApp", null));
        assertEquals(GatewayCompatibilityFrameKind.UNKNOWN, result);
    }

    @Test
    void builtinPingHandlerReturnsPongJson() {
        JsonObject ping = frame("PING", "heartbeat", false, null, null);

        GatewayCompatibilityFrameKind result = classifier.classify(ping);
        assertEquals(GatewayCompatibilityFrameKind.PING_HEARTBEAT, result);

        JsonObject pong = codec.parseObject(classifier.encodeHeartbeatPong(ping));
        assertNotNull(pong);
        assertEquals("PONG", pong.get("msgType").getAsString());
        assertEquals("heartbeat", pong.get("subMsgType").getAsString());
        assertTrue(pong.get("response").getAsBoolean());
        assertEquals("worker-1", pong.getAsJsonObject("context").get("workerId").getAsString());
    }

    @Test
    void builtinPongHandlerReturnsWithoutSideEffects() {
        JsonObject pong = frame("PONG", "heartbeat", false, null, null);

        GatewayCompatibilityFrameKind result = classifier.classify(pong);
        assertEquals(GatewayCompatibilityFrameKind.PONG_HEARTBEAT, result);
        classifier.recordHeartbeatPong(pong);
    }

    @Test
    void classificationUsesTupleOnlyAsAdapterCompatibility() {
        GatewayCompatibilityFrameKind result = classifier.classify(frame("TASK", "step", false, null, null));
        assertEquals(GatewayCompatibilityFrameKind.TASK_STEP, result);
    }

    @Test
    void controlEventRequestCanBeDecodedIntoCanonicalRequest() {
        JsonObject frame = frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, false, "demoApp", payload(
                WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page",
                WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-bridge",
                WorkerControlEventProtocol.PAYLOAD_FIELD, payload("url", "https://example.test"),
                WorkerControlEventProtocol.PRINCIPAL_FIELD, payload(
                        WorkerControlEventProtocol.CLIENT_ID_FIELD, "client-a",
                        WorkerControlEventProtocol.USER_ID_FIELD, "user-a"
                )
        ));

        GatewayCompatibilityFrameKind result = classifier.classify(frame);
        assertEquals(GatewayCompatibilityFrameKind.CONTROL_EVENT_REQUEST, result);

        var request = codec.decodeControlEventRequest(frame);
        var principal = codec.decodeControlEventPrincipal(frame);
        EventResponse response = EventResponse.success(
                Map.of("echoEvent", request.getEvent().value()),
                request.getRequestId()
        );
        JsonObject encodedResponse = codec.parseObject(codec.encodeControlEventResponse(frame, response));

        assertEquals("crawler.fetch-page", request.getEvent().value());
        assertEquals("req-bridge", request.getRequestId());
        assertEquals("https://example.test", request.getPayload().get("url"));
        assertEquals("client-a", principal.getClientId());
        assertEquals("user-a", principal.getUserId());
        assertTrue(encodedResponse.getAsJsonObject("payload").get("success").getAsBoolean());
        assertEquals("req-bridge",
                encodedResponse.getAsJsonObject("payload").get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
    }

    @Test
    void controlEventRequestAcceptsCanonicalEventCodeField() {
        JsonObject frame = frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, false, "demoApp", payload(
                WorkerControlEventProtocol.EVENT_CODE_FIELD, "crawler.fetch-page",
                WorkerControlEventProtocol.REQUEST_ID_FIELD, "req-canonical",
                WorkerControlEventProtocol.PAYLOAD_FIELD, payload("url", "https://example.test")
        ));

        GatewayCompatibilityFrameKind result = classifier.classify(frame);
        assertEquals(GatewayCompatibilityFrameKind.CONTROL_EVENT_REQUEST, result);

        var request = codec.decodeControlEventRequest(frame);
        assertEquals("crawler.fetch-page", request.getEvent().value());
        assertEquals("req-canonical", request.getRequestId());
        assertEquals("https://example.test", request.getPayload().get("url"));
    }

    @Test
    void controlEventResponseFramesAreNotClassifiedAsRequests() {
        JsonObject frame = frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, true, "demoApp", payload(
                WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page"
        ));

        GatewayCompatibilityFrameKind result = classifier.classify(frame);
        assertEquals(GatewayCompatibilityFrameKind.CONTROL_EVENT_RESPONSE, result);
    }

    @Test
    void controlEventWithLegacySubtypeRemainsUnknown() {
        JsonObject frame = frame("CONTROL", "legacy", false, "demoApp", payload(
                WorkerControlEventProtocol.EVENT_FIELD, "crawler.fetch-page"
        ));

        GatewayCompatibilityFrameKind result = classifier.classify(frame);
        assertEquals(GatewayCompatibilityFrameKind.UNKNOWN, result);
    }

    @Test
    void controlEventWithoutExplicitEventCodeRemainsUnknown() {
        JsonObject frame = frame("CONTROL", WorkerControlEventProtocol.SUB_MSG_TYPE, false, "demoApp", new JsonObject());

        GatewayCompatibilityFrameKind result = classifier.classify(frame);
        assertEquals(GatewayCompatibilityFrameKind.UNKNOWN, result);
        assertNull(codec.extractEventCode(frame));
    }

    private JsonObject frame(String msgType, String subMsgType, boolean response, String project, JsonObject payload) {
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
        context.addProperty("connRole", "task_messages");
        frame.add("context", context);
        frame.add("payload", payload != null ? payload : new JsonObject());
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
