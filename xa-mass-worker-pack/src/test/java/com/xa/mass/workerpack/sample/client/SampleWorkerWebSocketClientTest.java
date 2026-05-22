package com.xa.mass.workerpack.sample.client;

import com.google.gson.JsonObject;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientState;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientStateRegistry;
import com.xa.mass.workerpack.sample.command.fixture.SampleWorkerFaultProfile;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import com.xa.mass.workerpack.testutil.WsFrameTestSupport;
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

class SampleWorkerWebSocketClientTest {

    private SampleClientStateRegistry stateRegistry;
    private ClientSessionManager clientSessionManager;

    @BeforeEach
    void setUp() {
        stateRegistry = new SampleClientStateRegistry();
        clientSessionManager = new ClientSessionManager();
        SampleCommandRuntime.registerService(SampleClientStateRegistry.class, stateRegistry);
        SampleCommandRuntime.registerService(ClientSessionManager.class, clientSessionManager);
    }

    @Test
    void taskRequestProducesSingleMockResponse() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        assertEquals(1, client.sentMessages.size());
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertEquals("msg-1", WsFrameTestSupport.messageId(response));
        assertEquals("worker-test", WsFrameTestSupport.workerId(response));
        assertEquals("task-1", WsFrameTestSupport.taskId(response));
        assertTrue(response.get("success").getAsBoolean());
        assertEquals("Executed by sample client worker-test", response.get("detail").getAsString());
        JsonObject payload = WsFrameTestSupport.payload(response);
        assertEquals("SUCCESS", payload.get("status").getAsString());
        assertEquals("Executed by sample client worker-test", payload.get("mockData").getAsString());
        assertTrue(payload.has("execution"));
        assertTrue(payload.has("workerProfile"));
        JsonObject execution = payload.getAsJsonObject("execution");
        assertEquals("websocket", execution.get("adapterId").getAsString());
        assertEquals("realtime", execution.get("transportHint").getAsString());
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
    void defaultConstructorUsesDefaultWebSocketPort() {
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient("worker-test");

        assertEquals("ws://localhost:18088/ws?workerId=worker-test", client.getURI().toString());
    }

    @Test
    void taskResponseDoesNotTriggerAnotherMockResponse() {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");

        client.onMessage(taskMessage(true));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanProduceFailedMockResponseWhenConfigured() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test", "FAILED");

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
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDropMode(SampleClientState.DropMode.ALWAYS);

        client.onMessage(taskMessage(false));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanBeDelayedByMockState() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDelayMillis(150L);

        client.onMessage(taskMessage(false));

        Thread.sleep(50L);
        assertTrue(client.sentMessages.isEmpty());

        assertTrue(client.awaitSentCount(1, 1000L));
    }

    @Test
    void taskRequestCanBeDelayedByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.SLOW, 42L)
                        .delay(120L, 120L, SampleWorkerFaultProfile.DelayDistribution.FIXED)
                        .build()
        );

        client.onMessage(taskMessage(false));

        Thread.sleep(50L);
        assertTrue(client.sentMessages.isEmpty());

        assertTrue(client.awaitSentCount(1, 1000L));
    }

    @Test
    void taskRequestCanBeDroppedByFaultProfile() {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.FLAKY_RESULT, 42L)
                        .resultDrop(SampleWorkerFaultProfile.ResultDropMode.ALWAYS, 0)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void msgIdOnlyTaskFrameIsIgnored() {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
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
    void legacyTupleTaskFrameIsIgnored() {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "legacy-1");
        frame.addProperty("workerId", "worker-test");
        frame.addProperty("msgType", "TASK");
        frame.addProperty("subMsgType", "step");
        JsonObject payload = new JsonObject();
        payload.add("steps", new com.google.gson.JsonArray());
        frame.add("payload", payload);

        client.onMessage(frame.toString());

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void sampleStateGetTaskReturnsStateSnapshotInTaskOutput() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
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
    void faultProfileTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("profile", "NOISY");
        input.addProperty("seed", 77L);

        client.onMessage(taskMessage("fault.execution.profile", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.execution.profile", output.get("eventCode").getAsString());
        assertEquals("fault_profile_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("NOISY", faultProfile.get("profile").getAsString());
        assertEquals(77L, faultProfile.get("seed").getAsLong());
    }

    @Test
    void sampleDisconnectTaskClosesClientAfterTaskResult() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
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

    private static class CapturingSampleWorkerClient extends SampleWorkerWebSocketClient {
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeInvoked = new AtomicBoolean(false);

        private CapturingSampleWorkerClient(String workerId) {
            this(workerId, "SUCCESS");
        }

        private CapturingSampleWorkerClient(String workerId, String taskResultStatus) {
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
