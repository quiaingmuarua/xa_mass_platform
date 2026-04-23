package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.TaskStep;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.command.mock.MockClientState;
import com.xa.mass.mock.command.mock.MockClientStateRegistry;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MassWebSocketClientImplTest {

    private final Gson gson = new Gson();
    private MockClientStateRegistry stateRegistry;
    private ClientSessionManager clientSessionManager;

    @BeforeEach
    void setUp() {
        stateRegistry = new MockClientStateRegistry();
        clientSessionManager = new ClientSessionManager();
        MockCommandRuntime.registerService(MockClientStateRegistry.class, stateRegistry);
        MockCommandRuntime.registerService(ClientSessionManager.class, clientSessionManager);
    }

    @Test
    void taskRequestProducesSingleMockResponse() {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("worker-test");

        client.onMessage(gson.toJson(taskMessage(false)));

        assertEquals(1, client.sentMessages.size());
        MassMessage response = gson.fromJson(client.sentMessages.get(0), MassMessage.class);
        assertTrue(response.isResponse());
        assertEquals("msg-1", response.getMsgId());
        assertEquals(MessageType.TASK, response.getMsgType());
        assertEquals("step", response.getSubMsgType());
    }

    @Test
    void defaultConstructorUsesGatewayPort() {
        MassWebSocketClientImpl client = new MassWebSocketClientImpl("worker-test");

        assertEquals("ws://localhost:18088/ws", client.getURI().toString());
    }

    @Test
    void taskResponseDoesNotTriggerAnotherMockResponse() {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("worker-test");

        client.onMessage(gson.toJson(taskMessage(true)));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanProduceFailedMockResponseWhenConfigured() {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("worker-test", "FAILED");

        client.onMessage(gson.toJson(taskMessage(false)));

        assertEquals(1, client.sentMessages.size());
        MassMessage response = gson.fromJson(client.sentMessages.get(0), MassMessage.class);
        assertEquals("FAILED", response.getPayload().getAsJsonObject().get("status").getAsString());
    }

    @Test
    void taskRequestCanBeDroppedByMockState() {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDropMode(MockClientState.DropMode.ALWAYS);

        client.onMessage(gson.toJson(taskMessage(false)));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanBeDelayedByMockState() throws Exception {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDelayMillis(150L);

        client.onMessage(gson.toJson(taskMessage(false)));

        Thread.sleep(50L);
        assertTrue(client.sentMessages.isEmpty());

        assertTrue(client.awaitSentCount(1, 1000L));
    }

    @Test
    void mockDisconnectClosesClientAfterAck() throws Exception {
        CapturingMassWebSocketClient client = new CapturingMassWebSocketClient("worker-test");
        clientSessionManager.addClient(client);

        client.onMessage(gson.toJson(eventControlMessage("worker-test", "mock.disconnect")));

        assertTrue(client.awaitSentCount(1, 1000L));
        MassMessage response = gson.fromJson(client.sentMessages.get(0), MassMessage.class);
        assertEquals(MessageType.EVENT, response.getMsgType());
        assertEquals(WorkerControlEventProtocol.SUB_MSG_TYPE, response.getSubMsgType());
        assertTrue(client.awaitClosed(1000L));
        assertFalse(client.isOpen());
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
        context.setWorkerId("worker-test");
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

    private MassMessage eventControlMessage(String workerId, String event) {
        MassMessage message = new MassMessage();
        message.setMsgId("control-1");
        message.setResponse(false);
        message.setMsgType(MessageType.CONTROL);
        message.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
        message.setFrom(MessageDirection.SERVER);
        message.setProject("demoApp");

        MessageContext context = new MessageContext();
        context.setWorkerId(workerId);
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        message.setContext(context);

        JsonObject payload = new JsonObject();
        payload.addProperty(WorkerControlEventProtocol.EVENT_FIELD, event);
        payload.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, "debug-request-1");
        JsonObject eventPayload = new JsonObject();
        eventPayload.addProperty("workerId", workerId);
        payload.add(WorkerControlEventProtocol.PAYLOAD_FIELD, eventPayload);
        message.setPayload(payload);
        return message;
    }

    private static class CapturingMassWebSocketClient extends MassWebSocketClientImpl {
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeInvoked = new AtomicBoolean(false);

        private CapturingMassWebSocketClient(String workerId) {
            this(workerId, "SUCCESS");
        }

        private CapturingMassWebSocketClient(String workerId, String taskResultStatus) {
            super(URI.create("ws://127.0.0.1:65535/ws"), workerId, taskResultStatus);
        }

        @Override
        public void send(String text) {
            sentMessages.add(text);
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void closeConnection() {
            closeInvoked.set(true);
            open.set(false);
        }

        private boolean awaitSentCount(int expectedCount, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (sentMessages.size() >= expectedCount) {
                    return true;
                }
                Thread.sleep(20L);
            }
            return sentMessages.size() >= expectedCount;
        }

        private boolean awaitClosed(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (closeInvoked.get()) {
                    return true;
                }
                Thread.sleep(20L);
            }
            return closeInvoked.get();
        }
    }
}
