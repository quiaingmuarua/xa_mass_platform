package com.xa.mass.workerpack.sample.client;

import com.google.gson.JsonObject;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCapabilityReportSnapshot;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSubmitRequest;
import com.xa.mass.sdk.model.WorkerStateProjectionSnapshot;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientState;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientStateRegistry;
import com.xa.mass.workerpack.sample.command.fixture.SampleWorkerFaultProfile;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import com.xa.mass.workerpack.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleWorkerSocketClientTest {

    private SampleClientStateRegistry stateRegistry;
    private ClientSessionManager clientSessionManager;
    private CapturingWorkerControl workerControl;

    @BeforeEach
    void setUp() {
        stateRegistry = new SampleClientStateRegistry();
        clientSessionManager = new ClientSessionManager();
        workerControl = new CapturingWorkerControl();
        SampleCommandRuntime.registerService(SampleClientStateRegistry.class, stateRegistry);
        SampleCommandRuntime.registerService(ClientSessionManager.class, clientSessionManager);
        SampleCommandRuntime.registerService(WorkerControlOperations.class, workerControl);
    }

    @Test
    void taskRequestProducesCanonicalSocketResponse() throws Exception {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");

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
        assertEquals("sample-socket-client", payload.getAsJsonObject("workerProfile").get("runtime").getAsString());
    }

    @Test
    void taskResponseDoesNotTriggerAnotherSocketMockResponse() {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");

        client.handleInboundFrame(taskMessage(true));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanBeDroppedByMockState() {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setTaskResponseDropMode(SampleClientState.DropMode.ALWAYS);

        client.handleInboundFrame(taskMessage(false));

        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanDisconnectBeforeResultByFaultProfile() throws Exception {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.FLAKY_TRANSPORT, 42L)
                        .disconnectPhase(SampleWorkerFaultProfile.DisconnectPhase.BEFORE_RESULT)
                        .build()
        );

        client.handleInboundFrame(taskMessage(false));

        assertTrue(client.awaitClosed(1000L));
        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanDisconnectAfterResultByFaultProfile() throws Exception {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.FLAKY_TRANSPORT, 42L)
                        .disconnectPhase(SampleWorkerFaultProfile.DisconnectPhase.AFTER_RESULT)
                        .build()
        );

        client.handleInboundFrame(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        assertTrue(client.awaitClosed(1000L));
        assertEquals("msg-1", WsFrameTestSupport.messageId(WsFrameTestSupport.parse(client.sentMessages.get(0))));
    }

    @Test
    void sampleDisconnectTaskClosesSocketClientAfterTaskResult() throws Exception {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");
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

    @Test
    void realtimeWorkerCommandFrameAcknowledgesThroughWorkerControl() {
        CapturingSampleWorkerSocketClient client = new CapturingSampleWorkerSocketClient("worker-test");
        JsonObject frame = workerCommandFrame("cmd-1", "worker-test", "PING");

        client.handleInboundFrame(frame.toString());

        assertTrue(client.sentMessages.isEmpty());
        assertEquals(1, workerControl.commandAcknowledgements.size());
        WorkerCommandAcknowledgementRequest acknowledgement = workerControl.commandAcknowledgements.get(0);
        assertEquals("cmd-1", acknowledgement.commandId());
        assertEquals("SUCCEEDED", acknowledgement.status());
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

    private JsonObject workerCommandFrame(String commandId, String workerId, String commandType) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "worker.command");
        frame.addProperty("commandId", commandId);
        frame.addProperty("workerId", workerId);
        frame.addProperty("commandType", commandType);
        frame.add("payload", new JsonObject());
        return frame;
    }

    private static class CapturingSampleWorkerSocketClient extends SampleWorkerSocketClient {
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeInvoked = new AtomicBoolean(false);

        private CapturingSampleWorkerSocketClient(String workerId) {
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

    private static final class CapturingWorkerControl implements WorkerControlOperations {
        private final List<WorkerCommandAcknowledgementRequest> commandAcknowledgements = new ArrayList<>();

        @Override
        public WorkerCapabilityReportSnapshot reportWorkerCapability(WorkerCapabilityReportRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public WorkerStateReportSnapshot reportWorkerState(WorkerStateReportRequest request) {
            WorkerStateProjectionSnapshot projection = new WorkerStateProjectionSnapshot(
                    request.workerId(),
                    request.stateVersion(),
                    request.state(),
                    request.reason(),
                    request.observedAt(),
                    Instant.now()
            );
            return new WorkerStateReportSnapshot(
                    "ACCEPTED",
                    request.workerId(),
                    request.stateVersion(),
                    true,
                    true,
                    request.reason(),
                    projection
            );
        }

        @Override
        public WorkerStateProjectionSnapshot getWorkerStateProjection(String workerId) {
            return null;
        }

        @Override
        public List<WorkerStateProjectionSnapshot> listWorkerStateProjections() {
            return List.of();
        }

        @Override
        public WorkerCommandResultSnapshot requestWorkerCommand(WorkerCommandSubmitRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public WorkerCommandResultSnapshot acknowledgeWorkerCommand(WorkerCommandAcknowledgementRequest request) {
            commandAcknowledgements.add(request);
            return null;
        }

        @Override
        public List<WorkerCommandSnapshot> pullWorkerCommands(String workerId, int maxCommands) {
            return List.of();
        }

        @Override
        public WorkerCommandSnapshot getWorkerCommand(String commandId) {
            return null;
        }

        @Override
        public List<WorkerCommandSnapshot> listWorkerCommandsForWorker(String workerId) {
            return List.of();
        }
    }
}
