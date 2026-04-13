package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.TaskStep;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.session.SessionRoles;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassWebSocketClientImplTest {

    private final Gson gson = new Gson();

    @Test
    void taskRequestProducesSingleMockResponse() {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("device-test");

        client.onMessage(gson.toJson(taskMessage(false)));

        assertEquals(1, client.sentMessages.size());
        MassMessage response = gson.fromJson(client.sentMessages.get(0), MassMessage.class);
        assertTrue(response.isResponse());
        assertEquals("msg-1", response.getMsgId());
        assertEquals(MessageType.TASK, response.getMsgType());
        assertEquals("step", response.getSubMsgType());
    }

    @Test
    void taskResponseDoesNotTriggerAnotherMockResponse() {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("device-test");

        client.onMessage(gson.toJson(taskMessage(true)));

        assertTrue(client.sentMessages.isEmpty());
    }

    private MassMessage taskMessage(boolean response) {
        MassMessage message = new MassMessage();
        message.setMsgId("msg-1");
        message.setResponse(response);
        message.setMsgType(MessageType.TASK);
        message.setSubMsgType("step");
        message.setFrom(MessageDirection.SERVER);
        message.setProject("demoApp");

        MessageContext context = new MessageContext();
        context.setDeviceId("device-test");
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        context.setTid("task-1");
        message.setContext(context);

        TaskPayload payload = new TaskPayload();
        List<TaskStep> steps = new ArrayList<>();
        TaskStep step = new TaskStep();
        step.setStepId("step-1");
        steps.add(step);
        payload.setSteps(steps);
        message.setPayload(JsonParser.parseString(gson.toJson(payload)));
        return message;
    }

    private static class CapturingMassWebSocketClient extends MassWebSocketClientImpl {
        private final List<String> sentMessages = new ArrayList<>();

        private CapturingMassWebSocketClient(String deviceId) {
            super(URI.create("ws://127.0.0.1:65535/ws"), deviceId);
        }

        @Override
        public void send(String text) {
            sentMessages.add(text);
        }
    }
}
