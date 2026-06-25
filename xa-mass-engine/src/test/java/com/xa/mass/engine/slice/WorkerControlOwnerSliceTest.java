package com.xa.mass.engine.slice;

import com.xa.mass.engine.InMemoryWorkerDeclarationRuntimeStore;

import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.engine.command.WorkerCommandRequestEventHandler;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.worker.runtime.command.WorkerCommandStatus;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.control.WorkerControlService;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.worker.runtime.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.engine.control.WorkerStateReportEventHandler;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerControlOwnerSliceTest {

    @Test
    void commandAndStateHandlersShareKernelRuntimeWithoutCrossWritingOwners() {
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerStateProjectionOwner stateOwner = new WorkerStateProjectionOwner();
        RecordingEventSink sink = new RecordingEventSink();
        TraceEventLogger trace = new TraceEventLogger(sink);
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerControlService workerControlService = new WorkerControlService(
                workerManager,
                workerManager,
                new DefaultWorkerDispatchAvailabilityPolicy(workerManager, workerManager),
                commandOwner,
                stateOwner,
                trace);
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        KernelEventHandlerRegistry registry = new KernelEventHandlerRegistry(runtime);

        new WorkerCommandRequestEventHandler(workerControlService).register(registry);
        new WorkerStateReportEventHandler(workerControlService).register(registry);

        CoreEventResponse commandResponse = runtime.dispatch(commandRequest("cmd-1", "worker-1"),
                new CoreEventPrincipal("operator-1", "operator"));

        assertTrue(commandResponse.isSuccess());
        assertEquals(WorkerCommandStatus.REQUESTED, commandOwner.command("cmd-1").orElseThrow().status());
        assertTrue(stateOwner.projection("worker-1").isEmpty());
        sink.assertHasEvent(ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION, "commandId", "cmd-1");
        assertFalse(sink.hasEvent(ExecutionEventType.WORKER_STATE_REPORT_APPLIED));

        CoreEventResponse stateResponse = runtime.dispatch(stateReport("worker-1", 1, "READY"),
                new CoreEventPrincipal("worker-1", "worker"));

        assertTrue(stateResponse.isSuccess());
        assertEquals("READY", stateOwner.projection("worker-1").orElseThrow().state());
        assertTrue(commandOwner.command("state-report-1").isEmpty());
        assertEquals(1, sink.eventsOfType(ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION).size());
        assertEquals(1, sink.eventsOfType(ExecutionEventType.WORKER_STATE_REPORT_APPLIED).size());
    }

    private static CoreEventRequest commandRequest(String commandId, String workerId) {
        return CoreEventRequest.builder()
                .event(WorkerCommandRequestEventHandler.EVENT_CODE)
                .requestId("request-" + commandId)
                .payload(Map.of(
                        "commandId", commandId,
                        "workerId", workerId,
                        "commandType", "DRAIN",
                        "requester", "operator-1",
                        "reason", "maintenance",
                        "idempotencyKey", "idem-" + commandId
                ))
                .build();
    }

    private static CoreEventRequest stateReport(String workerId, long version, String state) {
        return CoreEventRequest.builder()
                .event(WorkerStateReportEventHandler.EVENT_CODE)
                .requestId("state-report-" + version)
                .payload(Map.of(
                        "workerId", workerId,
                        "stateVersion", version,
                        "state", state,
                        "reason", "slice-test",
                        "observedAtEpochMillis", 1_779_000_000_000L + version
                ))
                .build();
    }
}
