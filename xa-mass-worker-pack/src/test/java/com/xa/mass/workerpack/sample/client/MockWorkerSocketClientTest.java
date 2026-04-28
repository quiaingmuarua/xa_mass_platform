package com.xa.mass.workerpack.sample.client;

import com.google.gson.JsonObject;
import com.xa.mass.workerpack.sample.command.fixture.MockClientState;
import com.xa.mass.workerpack.sample.command.fixture.MockClientStateRegistry;
import com.xa.mass.workerpack.sample.command.runtime.MockCommandRuntime;
import com.xa.mass.workerpack.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockWorkerSocketClientTest {

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
    void taskRequestProducesCanonicalSocketResponse() throws Exception {
        CapturingMockWorkerSocketClient client = new CapturingMockWorkerSocketClient("worker-test");

        client.handleInboundFrame(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertEquals("msg-1", WsFrameTestSupport.messageId(response));
        assertEquals("worker-test", WsFrameTestSupport.workerId(response));
        assertEquals("task-1", WsFrameTestSupport.taskId(response));
        assertTrue(response.get("success").getAsBoolean());
        JsonObject payload = WsFrameTestSupport.payload(response);
        JsonObject execution = payload.getAsJsonObject("execution");
        assertEquals("socket", execution.get("adapterId").getAsString());
        assertEquals("realtime", execution.get("transportHint").getAsString());
        assertEquals("mock-socket-client", payload.getAsJsonObject("workerProfile").get("runtime").getAsString());
    }

    @Test
    void taskResponseDoesNotTriggerAnotherSocketMockResponse() {
        CapturingMockWorkerSocketClient client = new CapturingMockWorkerSocketClient("worker-test");

        client.handleInboundFrame(taskMessage(true));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanBeDroppedByMockState() {
        CapturingMockWorkerSocketClient client = new CapturingMockWorkerSocketClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDropMode(MockClientState.DropMode.ALWAYS);

        client.handleInboundFrame(taskMessage(false));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void mockDisconnectTaskClosesSocketClientAfterTaskResult() throws Exception {
        CapturingMockWorkerSocketClient client = new CapturingMockWorkerSocketClient("worker-test");
        clientSessionManager.addClient(client);

        client.handleInboundFrame(taskMessage("mock.disconnect", new JsonObject()));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        JsonObject output = WsFrameTestSupport.payload(response);
        assertEquals("mock.disconnect", output.get("eventCode").getAsString());
        assertTrue(output.get("disconnectAfterAck").getAsBoolean());
        assertEquals("worker-test", output.get("disconnectWorkerId").getAsString());
        assertTrue(client.awaitClosed(1000L));
        assertFalse(client.isConnected());
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
            payload.addProperty("target", "socket-target");
        }
        String raw = WsFrameTestSupport.buildTaskDispatch("msg-1", "demoApp", "worker-test", "task-1", eventCode, payload);
        if (!response) {
            return raw;
        }
        return WsFrameTestSupport.buildTaskResult("msg-1", "demoApp", "worker-test", "task-1", "SUCCESS", "prebuilt");
    }

    private static class CapturingMockWorkerSocketClient extends MockWorkerSocketClient {
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeInvoked = new AtomicBoolean(false);

        private CapturingMockWorkerSocketClient(String workerId) {
            super(URI.create("tcp://127.0.0.1:65535"), workerId, "SUCCESS");
        }

        @Override
        protected void sendFrame(String frameJson) {
            sentMessages.add(frameJson);
        }

        @Override
        protected boolean isSocketOpen() {
            return open.get();
        }

        @Override
        protected synchronized void closeConnection() {
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
