package com.xa.mass.gateway.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageParserTest {

    private final GsonMessageCodec codec = new GsonMessageCodec();
    private final MessageParser parser = new MessageParser(codec);

    @Test
    void extractEventCodePrefersExplicitPayloadField() {
        JsonObject frame = baseFrame();
        frame.getAsJsonObject("payload").addProperty("eventCode", "crawler.fetch-page");

        JsonObject decoded = parser.tryDecode(codec.getGson().toJson(frame));

        assertEquals("crawler.fetch-page", parser.extractEventCode(decoded));
    }

    @Test
    void extractEventCodeSupportsControlEventPayload() {
        JsonObject frame = baseFrame();
        frame.getAsJsonObject("payload").addProperty(WorkerControlEventProtocol.EVENT_FIELD, "mock.state.get");

        JsonObject decoded = parser.tryDecode(codec.getGson().toJson(frame));

        assertEquals("mock.state.get", parser.extractEventCode(decoded));
    }

    @Test
    void extractEventCodeDoesNotPeekIntoTaskPayloadInternals() {
        JsonObject frame = baseFrame();
        frame.addProperty("msgType", "TASK");
        JsonObject payload = new JsonObject();
        JsonObject sdk = new JsonObject();
        sdk.addProperty("eventCode", "legacy.hidden.event");
        JsonObject params = new JsonObject();
        params.add("_sdk", sdk);
        JsonObject step = new JsonObject();
        step.add("params", params);
        JsonArray steps = new JsonArray();
        steps.add(step);
        payload.add("steps", steps);
        frame.add("payload", payload);

        JsonObject decoded = parser.tryDecode(codec.getGson().toJson(frame));

        assertNull(parser.extractEventCode(decoded));
    }

    @Test
    void parserValidatesWorkerContextOnly() {
        JsonObject frame = baseFrame();
        JsonObject decoded = parser.tryDecode(codec.getGson().toJson(frame));

        assertNotNull(decoded);
        assertTrue(parser.isValid(decoded));
        assertEquals("worker-1", parser.extractWorkerId(decoded));
        assertEquals("task_messages", parser.extractConnRole(decoded));
        assertEquals("msg-1", parser.extractMessageId(decoded));
        assertEquals("demoApp", parser.extractProject(decoded));
    }

    @Test
    void invalidJsonReturnsNull() {
        JsonObject decoded = parser.tryDecode("{not-json");
        assertNull(decoded);
    }

    @Test
    void missingWorkerIdIsInvalid() {
        JsonObject frame = baseFrame();
        frame.remove("context");
        JsonObject decoded = parser.tryDecode(codec.getGson().toJson(frame));

        assertNotNull(decoded);
        assertFalse(parser.isValid(decoded));
    }

    private JsonObject baseFrame() {
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", "msg-1");
        frame.addProperty("project", "demoApp");
        frame.addProperty("msgType", "CONTROL");
        frame.addProperty("subMsgType", "event");
        JsonObject context = new JsonObject();
        context.addProperty("workerId", "worker-1");
        context.addProperty("connRole", "task_messages");
        frame.add("context", context);
        frame.add("payload", new JsonObject());
        return frame;
    }
}
