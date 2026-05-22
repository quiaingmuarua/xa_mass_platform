package com.xa.mass.workerpack.sample.command.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandResponse;
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
import com.xa.mass.workerpack.sample.client.ClientSessionManager;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientStateRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandRuntimeTest {

    @BeforeAll
    static void setUpRuntime() {
        SampleCommandRuntime.registerService(SampleClientStateRegistry.class, new SampleClientStateRegistry());
        SampleCommandRuntime.registerService(ClientSessionManager.class, new ClientSessionManager());
        SampleCommandRuntime.registerService(WorkerControlOperations.class, new CapturingWorkerControl());
    }

    @Test
    void shouldExecuteMockStateGetCommand() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "mock.state.get");
        request.addProperty("workerId", "worker-001");

        CommandResponse<?> response = SampleCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("worker-001", data.get("workerId"));
        assertNotNull(data.get("state"));
    }

    @Test
    void shouldExecuteToolTimeNowCommand() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "tool.time.now");
        request.addProperty("zoneId", "Asia/Shanghai");

        CommandResponse<?> response = SampleCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("Asia/Shanghai", data.get("zoneId"));
        assertEquals(false, data.get("simulated"));
        assertNotNull(data.get("iso8601"));
    }

    @Test
    void shouldPersistMockDelayStateForWorker() {
        JsonObject updateRequest = new JsonObject();
        updateRequest.addProperty("event", "mock.delay.response");
        updateRequest.addProperty("workerId", "worker-001");
        updateRequest.addProperty("millis", 250);

        CommandResponse<?> updateResponse = SampleCommandRuntime.dispatch(updateRequest);

        assertTrue(updateResponse.isSuccess());

        JsonObject stateRequest = new JsonObject();
        stateRequest.addProperty("event", "mock.state.get");
        stateRequest.addProperty("workerId", "worker-001");

        CommandResponse<?> stateResponse = SampleCommandRuntime.dispatch(stateRequest);

        assertTrue(stateResponse.isSuccess());
        assertTrue(stateResponse.getData() instanceof Map);
        Map<?, ?> stateData = (Map<?, ?>) stateResponse.getData();
        assertTrue(stateData.get("state") instanceof Map);
        assertEquals(250L, ((Map<?, ?>) stateData.get("state")).get("taskResponseDelayMillis"));
    }

    @Test
    void shouldPersistFaultProfileStateForWorker() {
        JsonObject profileRequest = new JsonObject();
        profileRequest.addProperty("event", "fault.execution.profile");
        profileRequest.addProperty("workerId", "worker-fault");
        profileRequest.addProperty("profile", "NOISY");
        profileRequest.addProperty("seed", 123L);

        CommandResponse<?> profileResponse = SampleCommandRuntime.dispatch(profileRequest);

        assertTrue(profileResponse.isSuccess());

        JsonObject stateRequest = new JsonObject();
        stateRequest.addProperty("event", "fault.state.get");
        stateRequest.addProperty("workerId", "worker-fault");

        CommandResponse<?> stateResponse = SampleCommandRuntime.dispatch(stateRequest);

        assertTrue(stateResponse.isSuccess());
        Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) stateResponse.getData()).get("state");
        Map<?, ?> faultProfile = (Map<?, ?>) state.get("faultProfile");
        assertEquals(true, faultProfile.get("enabled"));
        assertEquals("NOISY", faultProfile.get("profile"));
        assertEquals(123L, faultProfile.get("seed"));
    }

    @Test
    void shouldRejectInvalidFaultProfileConfig() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "fault.execution.profile");
        request.addProperty("workerId", "worker-fault-invalid");
        request.addProperty("profile", "UNKNOWN");

        CommandResponse<?> response = SampleCommandRuntime.dispatch(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("unsupported fault profile"));
    }

    @Test
    void shouldPersistFaultStallStateForWorker() {
        JsonObject stallRequest = new JsonObject();
        stallRequest.addProperty("event", "fault.execution.stall");
        stallRequest.addProperty("workerId", "worker-fault-stall");
        stallRequest.addProperty("until", "lease-expiry");

        CommandResponse<?> stallResponse = SampleCommandRuntime.dispatch(stallRequest);

        assertTrue(stallResponse.isSuccess());
        Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) stallResponse.getData()).get("state");
        Map<?, ?> faultProfile = (Map<?, ?>) state.get("faultProfile");
        assertEquals("STUCK", faultProfile.get("profile"));
        assertEquals("LEASE_EXPIRY", faultProfile.get("stallMode"));
    }

    @Test
    void shouldPersistFaultDuplicateResultStateForWorker() {
        JsonObject duplicateRequest = new JsonObject();
        duplicateRequest.addProperty("event", "fault.result.duplicate");
        duplicateRequest.addProperty("workerId", "worker-fault-duplicate");
        duplicateRequest.addProperty("count", 2);
        duplicateRequest.addProperty("gapMs", 10);

        CommandResponse<?> duplicateResponse = SampleCommandRuntime.dispatch(duplicateRequest);

        assertTrue(duplicateResponse.isSuccess());
        Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) duplicateResponse.getData()).get("state");
        Map<?, ?> faultProfile = (Map<?, ?>) state.get("faultProfile");
        assertEquals("FLAKY_RESULT", faultProfile.get("profile"));
        assertEquals(2, faultProfile.get("duplicateResultCount"));
        assertEquals(10L, faultProfile.get("duplicateResultGapMillis"));
    }

    @Test
    void shouldPersistFaultMalformedResultStateForWorker() {
        JsonObject malformedRequest = new JsonObject();
        malformedRequest.addProperty("event", "fault.result.malformed");
        malformedRequest.addProperty("workerId", "worker-fault-malformed");
        malformedRequest.addProperty("kind", "invalid-status");

        CommandResponse<?> malformedResponse = SampleCommandRuntime.dispatch(malformedRequest);

        assertTrue(malformedResponse.isSuccess());
        Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) malformedResponse.getData()).get("state");
        Map<?, ?> faultProfile = (Map<?, ?>) state.get("faultProfile");
        assertEquals("MALFORMED_RESULT", faultProfile.get("profile"));
        assertEquals("INVALID_STATUS", faultProfile.get("malformedResultKind"));
    }

    @Test
    void shouldPersistFaultTransportDisconnectStateForWorker() {
        JsonObject disconnectRequest = new JsonObject();
        disconnectRequest.addProperty("event", "fault.transport.disconnect");
        disconnectRequest.addProperty("workerId", "worker-fault-disconnect");
        disconnectRequest.addProperty("phase", "after-result");

        CommandResponse<?> disconnectResponse = SampleCommandRuntime.dispatch(disconnectRequest);

        assertTrue(disconnectResponse.isSuccess());
        Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) disconnectResponse.getData()).get("state");
        Map<?, ?> faultProfile = (Map<?, ?>) state.get("faultProfile");
        assertEquals("FLAKY_TRANSPORT", faultProfile.get("profile"));
        assertEquals("AFTER_RESULT", faultProfile.get("disconnectPhase"));
    }

    @Test
    void shouldReportFaultWorkerStateFlapThroughWorkerControl() {
        JsonObject stateFlapRequest = new JsonObject();
        stateFlapRequest.addProperty("event", "fault.worker.state.flap");
        stateFlapRequest.addProperty("workerId", "worker-fault-state");
        stateFlapRequest.addProperty("state", "draining");
        stateFlapRequest.addProperty("stateVersion", 123L);

        CommandResponse<?> stateFlapResponse = SampleCommandRuntime.dispatch(stateFlapRequest);

        assertTrue(stateFlapResponse.isSuccess());
        Map<?, ?> data = (Map<?, ?>) stateFlapResponse.getData();
        assertEquals("fault_worker_state_flap_reported", data.get("action"));
        assertEquals("DRAINING", data.get("reportedState"));
        assertEquals(123L, data.get("stateVersion"));
        Map<?, ?> report = (Map<?, ?>) data.get("report");
        assertEquals("ACCEPTED", report.get("status"));
        assertEquals(true, report.get("accepted"));
        Map<?, ?> projection = (Map<?, ?>) report.get("projection");
        assertEquals("DRAINING", projection.get("state"));
    }

    @Test
    void shouldResetFaultProfilesWithoutClearingMockState() {
        JsonObject mockDelay = new JsonObject();
        mockDelay.addProperty("event", "mock.delay.response");
        mockDelay.addProperty("workerId", "worker-fault-reset");
        mockDelay.addProperty("millis", 321);
        assertTrue(SampleCommandRuntime.dispatch(mockDelay).isSuccess());

        JsonObject faultProfile = new JsonObject();
        faultProfile.addProperty("event", "fault.execution.profile");
        faultProfile.addProperty("workerId", "worker-fault-reset");
        faultProfile.addProperty("profile", "NOISY");
        assertTrue(SampleCommandRuntime.dispatch(faultProfile).isSuccess());

        JsonObject resetFault = new JsonObject();
        resetFault.addProperty("event", "fault.reset");
        resetFault.addProperty("scope", "all");
        assertTrue(SampleCommandRuntime.dispatch(resetFault).isSuccess());

        JsonObject stateRequest = new JsonObject();
        stateRequest.addProperty("event", "fault.state.get");
        stateRequest.addProperty("workerId", "worker-fault-reset");
        CommandResponse<?> stateResponse = SampleCommandRuntime.dispatch(stateRequest);

        assertTrue(stateResponse.isSuccess());
        Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) stateResponse.getData()).get("state");
        Map<?, ?> resetProfile = (Map<?, ?>) state.get("faultProfile");
        assertEquals(321L, state.get("taskResponseDelayMillis"));
        assertEquals(false, resetProfile.get("enabled"));
    }

    @Test
    void shouldExecuteBatchCommandWithContextExport() {
        JsonObject request = new JsonObject();
        request.addProperty("event", "batch");
        request.addProperty("onError", "stop");

        JsonObject context = new JsonObject();
        context.addProperty("query", "Shanghai");
        context.addProperty("amount", 10);
        request.add("context", context);

        JsonObject geoParams = new JsonObject();
        geoParams.addProperty("query", "$ctx.query");
        JsonObject geoExport = new JsonObject();
        geoExport.addProperty("currency", "$result.currency");
        JsonObject geoStep = new JsonObject();
        geoStep.addProperty("id", "geo");
        geoStep.addProperty("event", "tool.geo.lookup");
        geoStep.add("params", geoParams);
        geoStep.add("export", geoExport);

        JsonObject quoteParams = new JsonObject();
        quoteParams.addProperty("base", "USD");
        quoteParams.addProperty("target", "$ctx.currency");
        quoteParams.addProperty("amount", "$ctx.amount");
        JsonObject quoteStep = new JsonObject();
        quoteStep.addProperty("id", "quote");
        quoteStep.addProperty("event", "tool.currency.quote");
        quoteStep.add("params", quoteParams);

        com.google.gson.JsonArray events = new com.google.gson.JsonArray();
        events.add(geoStep);
        events.add(quoteStep);
        request.add("events", events);

        CommandResponse<?> response = SampleCommandRuntime.dispatch(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertTrue(data.get("context") instanceof Map);
        assertEquals("CNY", ((Map<?, ?>) data.get("context")).get("currency"));
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
            throw new UnsupportedOperationException("not used");
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
