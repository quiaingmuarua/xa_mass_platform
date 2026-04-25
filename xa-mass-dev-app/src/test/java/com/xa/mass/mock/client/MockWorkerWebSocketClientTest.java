package com.xa.mass.mock.client;

import com.google.gson.JsonObject;
import com.xa.mass.mock.command.mock.MockClientState;
import com.xa.mass.mock.command.mock.MockClientStateRegistry;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import com.xa.mass.mock.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockWorkerWebSocketClientTest {

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
    void taskRequestProducesSingleMockResponse() throws Exception {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        assertEquals(1, client.sentMessages.size());
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertEquals("msg-1", WsFrameTestSupport.messageId(response));
        assertEquals("worker-test", WsFrameTestSupport.workerId(response));
        assertEquals("task-1", WsFrameTestSupport.taskId(response));
        assertTrue(response.get("success").getAsBoolean());
        assertEquals("Executed by mock client worker-test", response.get("detail").getAsString());
        JsonObject payload = WsFrameTestSupport.payload(response);
        assertEquals("SUCCESS", payload.get("status").getAsString());
        assertEquals("Executed by mock client worker-test", payload.get("mockData").getAsString());
        assertTrue(payload.has("execution"));
        assertTrue(payload.has("workerProfile"));
        JsonObject execution = payload.getAsJsonObject("execution");
        assertEquals("websocket", execution.get("transport").getAsString());
        assertEquals("task-1", execution.get("taskId").getAsString());
        assertEquals("msg-1", execution.get("messageId").getAsString());
        assertEquals("demoApp", execution.get("project").getAsString());
        assertEquals(1, execution.get("stepCount").getAsInt());
        assertEquals(0, execution.get("retryCount").getAsInt());
        long startedAt = execution.get("startedAtEpochMs").getAsLong();
        long finishedAt = execution.get("finishedAtEpochMs").getAsLong();
        long durationMs = execution.get("durationMs").getAsLong();
        assertTrue(durationMs >= 0);
        assertEquals(durationMs, finishedAt - startedAt);
        assertEquals("worker-test", payload.getAsJsonObject("workerProfile").get("workerId").getAsString());
    }

    @Test
    void defaultConstructorUsesGatewayPort() {
        MockWorkerWebSocketClient client = new MockWorkerWebSocketClient("worker-test");

        assertEquals("ws://localhost:18088/ws?workerId=worker-test", client.getURI().toString());
    }

    @Test
    void taskResponseDoesNotTriggerAnotherMockResponse() {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");

        client.onMessage(taskMessage(true));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanProduceFailedMockResponseWhenConfigured() throws Exception {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test", "FAILED");

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        assertEquals(1, client.sentMessages.size());
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertFalse(response.get("success").getAsBoolean());
        assertEquals("MOCK_TASK_FAILED", response.get("errorCode").getAsString());
        assertEquals("FAILED", WsFrameTestSupport.payload(response).get("status").getAsString());
    }

    @Test
    void taskRequestCanBeDroppedByMockState() {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDropMode(MockClientState.DropMode.ALWAYS);

        client.onMessage(taskMessage(false));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanBeDelayedByMockState() throws Exception {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDelayMillis(150L);

        client.onMessage(taskMessage(false));

        Thread.sleep(50L);
        assertTrue(client.sentMessages.isEmpty());

        assertTrue(client.awaitSentCount(1, 1000L));
    }

    @Test
    void msgIdOnlyTaskFrameIsIgnored() {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", "legacy-1");
        frame.addProperty("workerId", "worker-test");
        frame.addProperty("project", "demoApp");
        frame.addProperty("taskId", "task-1");
        frame.add("input", new JsonObject());

        client.onMessage(frame.toString());

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void mockStateGetTaskReturnsStateSnapshotInTaskOutput() throws Exception {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDelayMillis(275L);

        client.onMessage(taskMessage("mock.state.get", new JsonObject()));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertTrue(response.get("success").getAsBoolean());
        JsonObject output = WsFrameTestSupport.payload(response);
        assertEquals("mock.state.get", output.get("eventCode").getAsString());
        assertEquals("state", output.get("action").getAsString());
        JsonObject state = output.getAsJsonObject("state");
        assertEquals(275L, state.get("taskResponseDelayMillis").getAsLong());
        assertTrue(output.has("command"));
    }

    @Test
    void mockDisconnectTaskClosesClientAfterTaskResult() throws Exception {
        CapturingMockWorkerClient client = new CapturingMockWorkerClient("worker-test");
        clientSessionManager.addClient(client);

        client.onMessage(taskMessage("mock.disconnect", new JsonObject()));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertTrue(response.get("success").getAsBoolean());
        JsonObject output = WsFrameTestSupport.payload(response);
        assertEquals("mock.disconnect", output.get("eventCode").getAsString());
        assertTrue(output.get("disconnectAfterAck").getAsBoolean());
        assertEquals("worker-test", output.get("disconnectWorkerId").getAsString());
        assertTrue(client.awaitClosed(1000L));
        assertFalse(client.isOpen());
    }

    private String taskMessage(boolean response) {
        return taskMessage(response, "mock.task.dispatch", null);
    }

    private String taskMessage(String eventCode, JsonObject input) {
        return taskMessage(false, eventCode, input);
    }

    private String taskMessage(boolean response, String eventCode, JsonObject input) {
        JsonObject payload = new JsonObject();
        if (input != null) {
            for (String key : input.keySet()) {
                payload.add(key, input.get(key).deepCopy());
            }
        } else {
            com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
            JsonObject step = new JsonObject();
            step.addProperty("stepId", "step-1");
            steps.add(step);
            payload.add("steps", steps);
        }
        String raw = WsFrameTestSupport.buildTaskDispatch("msg-1", "demoApp", "worker-test", "task-1", eventCode, payload);
        if (!response) {
            return raw;
        }
        return WsFrameTestSupport.buildTaskResult("msg-1", "demoApp", "worker-test", "task-1", "SUCCESS", "prebuilt");
    }

    private static class CapturingMockWorkerClient extends MockWorkerWebSocketClient {
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeInvoked = new AtomicBoolean(false);

        private CapturingMockWorkerClient(String workerId) {
            this(workerId, "SUCCESS");
        }

        private CapturingMockWorkerClient(String workerId, String taskResultStatus) {
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
