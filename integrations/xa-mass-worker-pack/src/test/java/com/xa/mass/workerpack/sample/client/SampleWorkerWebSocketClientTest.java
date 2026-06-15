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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleWorkerWebSocketClientTest {

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
        assertFalse(execution.has("adapterId"));
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

        assertEquals("ws://localhost:18088/ws?workerId=worker-test&workerGroupId=sample-websocket-workers",
                client.getURI().toString());
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
    void taskRequestCanBeStalledWithoutResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.STUCK, 42L)
                        .stallMode(SampleWorkerFaultProfile.StallMode.LEASE_EXPIRY)
                        .build()
        );

        client.onMessage(taskMessage(false));

        Thread.sleep(100L);
        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanBeDelayedByDurationStallProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.STUCK, 42L)
                        .stallDuration(120L)
                        .build()
        );

        client.onMessage(taskMessage(false));

        Thread.sleep(50L);
        assertTrue(client.sentMessages.isEmpty());

        assertTrue(client.awaitSentCount(1, 1000L));
    }

    @Test
    void taskRequestCanSubmitDuplicateResultsByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.FLAKY_RESULT, 42L)
                        .duplicateResult(2, 10L)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(3, 1000L));
        assertEquals(3, client.sentMessages.size());
        assertEquals("msg-1", WsFrameTestSupport.messageId(WsFrameTestSupport.parse(client.sentMessages.get(0))));
        assertEquals("msg-1", WsFrameTestSupport.messageId(WsFrameTestSupport.parse(client.sentMessages.get(1))));
        assertEquals("msg-1", WsFrameTestSupport.messageId(WsFrameTestSupport.parse(client.sentMessages.get(2))));
    }

    @Test
    void taskRequestCanSubmitLateResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.NEAR_TIMEOUT, 42L)
                        .lateResultDelay(120L)
                        .build()
        );

        client.onMessage(taskMessage(false));

        Thread.sleep(50L);
        assertTrue(client.sentMessages.isEmpty());

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertEquals("msg-1", WsFrameTestSupport.messageId(response));
        assertTrue(WsFrameTestSupport.payload(response).getAsJsonObject("execution")
                .get("durationMs").getAsLong() >= 120L);
    }

    @Test
    void taskRequestCanSubmitMalformedResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.MALFORMED_RESULT, 42L)
                        .malformedResultKind(SampleWorkerFaultProfile.MalformedResultKind.MISSING_MESSAGE_ID)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertNull(WsFrameTestSupport.messageId(response));
        assertEquals("worker-test", WsFrameTestSupport.workerId(response));
        assertEquals("task-1", WsFrameTestSupport.taskId(response));
    }

    @Test
    void taskRequestCanSubmitInvalidStatusResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.MALFORMED_RESULT, 42L)
                        .malformedResultKind(SampleWorkerFaultProfile.MalformedResultKind.INVALID_STATUS)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertTrue(response.get("success").isJsonObject());
    }

    @Test
    void taskRequestCanSubmitWrongIdentityResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.WRONG_IDENTITY, 42L)
                        .resultIdentityKind(SampleWorkerFaultProfile.ResultIdentityKind.WRONG_MESSAGE)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject response = WsFrameTestSupport.parse(client.sentMessages.get(0));
        assertEquals("wrong-msg-1", WsFrameTestSupport.messageId(response));
        assertEquals("worker-test", WsFrameTestSupport.workerId(response));
        assertEquals("task-1", WsFrameTestSupport.taskId(response));
    }

    @Test
    void taskRequestCanDisconnectBeforeResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.FLAKY_TRANSPORT, 42L)
                        .disconnectPhase(SampleWorkerFaultProfile.DisconnectPhase.BEFORE_RESULT)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitClosed(1000L));
        assertTrue(client.sentMessages.isEmpty());
    }

    @Test
    void taskRequestCanDisconnectAfterResultByFaultProfile() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        stateRegistry.getOrCreate("worker-test").setFaultProfile(
                SampleWorkerFaultProfile.builder(SampleWorkerFaultProfile.ProfileName.FLAKY_TRANSPORT, 42L)
                        .disconnectPhase(SampleWorkerFaultProfile.DisconnectPhase.AFTER_RESULT)
                        .build()
        );

        client.onMessage(taskMessage(false));

        assertTrue(client.awaitSentCount(1, 1000L));
        assertTrue(client.awaitClosed(1000L));
        assertEquals("msg-1", WsFrameTestSupport.messageId(WsFrameTestSupport.parse(client.sentMessages.get(0))));
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
    void realtimeWorkerCommandFrameAcknowledgesThroughWorkerControl() {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject frame = workerCommandFrame("cmd-1", "worker-test", "PING");

        client.onMessage(frame.toString());

        assertTrue(client.sentMessages.isEmpty());
        assertEquals(1, workerControl.commandAcknowledgements.size());
        WorkerCommandAcknowledgementRequest acknowledgement = workerControl.commandAcknowledgements.get(0);
        assertEquals("cmd-1", acknowledgement.commandId());
        assertEquals("SUCCEEDED", acknowledgement.status());
    }

    @Test
    void realtimeWorkerCommandFrameForDifferentWorkerIsIgnored() {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject frame = workerCommandFrame("cmd-1", "other-worker", "PING");

        client.onMessage(frame.toString());

        assertTrue(client.sentMessages.isEmpty());
        assertTrue(workerControl.commandAcknowledgements.isEmpty());
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
    void faultStallTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("until", "forever");

        client.onMessage(taskMessage("fault.execution.stall", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.execution.stall", output.get("eventCode").getAsString());
        assertEquals("fault_stall_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("STUCK", faultProfile.get("profile").getAsString());
        assertEquals("FOREVER", faultProfile.get("stallMode").getAsString());
    }

    @Test
    void faultDuplicateTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("count", 2);
        input.addProperty("gapMs", 10);

        client.onMessage(taskMessage("fault.result.duplicate", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.result.duplicate", output.get("eventCode").getAsString());
        assertEquals("fault_result_duplicate_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("FLAKY_RESULT", faultProfile.get("profile").getAsString());
        assertEquals(2, faultProfile.get("duplicateResultCount").getAsInt());
        assertEquals(10L, faultProfile.get("duplicateResultGapMillis").getAsLong());
    }

    @Test
    void faultLateTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("delayPastLeaseMs", 1234L);

        client.onMessage(taskMessage("fault.result.late", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.result.late", output.get("eventCode").getAsString());
        assertEquals("fault_result_late_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("NEAR_TIMEOUT", faultProfile.get("profile").getAsString());
        assertEquals(1234L, faultProfile.get("lateResultDelayMillis").getAsLong());
    }

    @Test
    void faultMalformedTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("kind", "invalid-payload");

        client.onMessage(taskMessage("fault.result.malformed", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.result.malformed", output.get("eventCode").getAsString());
        assertEquals("fault_result_malformed_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("MALFORMED_RESULT", faultProfile.get("profile").getAsString());
        assertEquals("INVALID_PAYLOAD", faultProfile.get("malformedResultKind").getAsString());
    }

    @Test
    void faultIdentityTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("kind", "wrong-worker");

        client.onMessage(taskMessage("fault.result.identity", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.result.identity", output.get("eventCode").getAsString());
        assertEquals("fault_result_identity_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("WRONG_IDENTITY", faultProfile.get("profile").getAsString());
        assertEquals("WRONG_WORKER", faultProfile.get("resultIdentityKind").getAsString());
    }

    @Test
    void faultTransportDisconnectTaskUpdatesStateThroughCommandRuntime() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("phase", "after-result");

        client.onMessage(taskMessage("fault.transport.disconnect", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.transport.disconnect", output.get("eventCode").getAsString());
        assertEquals("fault_transport_disconnect_updated", output.get("action").getAsString());
        JsonObject faultProfile = output.getAsJsonObject("state").getAsJsonObject("faultProfile");
        assertEquals("FLAKY_TRANSPORT", faultProfile.get("profile").getAsString());
        assertEquals("AFTER_RESULT", faultProfile.get("disconnectPhase").getAsString());
    }

    @Test
    void faultWorkerStateFlapTaskReportsThroughWorkerControl() throws Exception {
        CapturingSampleWorkerClient client = new CapturingSampleWorkerClient("worker-test");
        JsonObject input = new JsonObject();
        input.addProperty("state", "offline");
        input.addProperty("stateVersion", 456L);

        client.onMessage(taskMessage("fault.worker.state.flap", input));

        assertTrue(client.awaitSentCount(1, 1000L));
        JsonObject output = WsFrameTestSupport.payload(WsFrameTestSupport.parse(client.sentMessages.get(0)));
        assertEquals("fault.worker.state.flap", output.get("eventCode").getAsString());
        assertEquals("fault_worker_state_flap_reported", output.get("action").getAsString());
        assertEquals("OFFLINE", output.get("reportedState").getAsString());
        JsonObject report = output.getAsJsonObject("report");
        assertEquals("ACCEPTED", report.get("status").getAsString());
        assertEquals("OFFLINE", report.getAsJsonObject("projection").get("state").getAsString());
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

    private JsonObject workerCommandFrame(String commandId, String workerId, String commandType) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "worker.command");
        frame.addProperty("commandId", commandId);
        frame.addProperty("workerId", workerId);
        frame.addProperty("commandType", commandType);
        frame.add("payload", new JsonObject());
        return frame;
    }

    private static class CapturingSampleWorkerClient extends SampleWorkerWebSocketClient {
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeInvoked = new AtomicBoolean(false);

        private CapturingSampleWorkerClient(String workerId) {
            this(workerId, "SUCCESS");
        }

        private CapturingSampleWorkerClient(String workerId, String taskResultStatus) {
            super(URI.create("ws://127.0.0.1:65535/ws"),
                    workerId,
                    "sample-websocket-workers",
                    taskResultStatus);
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

    private static final class CapturingWorkerControl implements WorkerControlOperations {
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

        private final List<WorkerCommandAcknowledgementRequest> commandAcknowledgements = new ArrayList<>();
    }
}
