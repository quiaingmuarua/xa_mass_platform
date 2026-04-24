package com.xa.mass.gateway.queue;

import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageParserTest {

    private final MessageParser parser = new MessageParser(new GsonMessageCodec());

    @Test
    void extractEventCodePrefersExplicitPayloadField() {
        MassMessage message = baseMessage();
        JsonObject payload = new JsonObject();
        payload.addProperty("eventCode", "crawler.fetch-page");
        message.setPayload(payload);

        Envelope envelope = parser.toStoredMessage("{}", message);

        assertEquals("crawler.fetch-page", envelope.getEventCode());
    }

    @Test
    void extractEventCodeSupportsControlEventPayload() {
        MassMessage message = baseMessage();
        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, "mock.state.get");
        message.setPayload(payload);

        Envelope envelope = parser.toStoredMessage("{}", message);

        assertEquals("mock.state.get", envelope.getEventCode());
    }

    @Test
    void extractEventCodeDoesNotPeekIntoTaskPayloadInternals() {
        MassMessage message = baseMessage();
        message.setMsgType(MessageType.TASK);
        JsonObject payload = new JsonObject();
        JsonObject sdk = new JsonObject();
        sdk.addProperty("eventCode", "legacy.hidden.event");
        JsonObject params = new JsonObject();
        params.add("_sdk", sdk);
        JsonObject step = new JsonObject();
        step.add("params", params);
        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        steps.add(step);
        payload.add("steps", steps);
        message.setPayload(payload);

        Envelope envelope = parser.toStoredMessage("{}", message);

        assertNull(envelope.getEventCode());
    }

    private MassMessage baseMessage() {
        MassMessage message = new MassMessage();
        message.setMsgId("msg-1");
        message.setProject("demoApp");
        message.setMsgType(MessageType.CONTROL);
        message.setSubMsgType("event");
        MessageContext context = new MessageContext();
        context.setWorkerId("worker-1");
        context.setConnRole("task_messages");
        message.setContext(context);
        return message;
    }
}
