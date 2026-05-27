package com.xa.mass.engine.worker;

import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.control.WorkerControlService;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.control.WorkerStateReportEventHandler;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.runtime.worker.WorkerStateProjectionStatus;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerStateReportEventHandlerTest {

    @Test
    void stateReportEventUpdatesProjectionAndEmitsTrace() {
        WorkerStateProjectionOwner owner = new WorkerStateProjectionOwner();
        RecordingEventSink sink = new RecordingEventSink();
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        WorkerStateReportEventHandler handler = new WorkerStateReportEventHandler(
                workerControlService(owner, new TraceEventLogger(sink)));
        handler.register(new KernelEventHandlerRegistry(runtime));

        CoreEventResponse response = runtime.dispatch(request(1, "READY"),
                new CoreEventPrincipal("worker-1", "worker"));

        assertTrue(response.isSuccess());
        assertEquals("READY", owner.projection("worker-1").orElseThrow().state());
        assertTrue(sink.events().stream()
                .anyMatch(event -> event.getEventType() == ExecutionEventType.WORKER_STATE_REPORT_APPLIED
                        && event.getIdentity().workerId().equals("worker-1")
                        && "ACCEPTED".equals(event.getAttrs().get("result"))
                        && "READY".equals(event.getAttrs().get("workerState"))));
    }

    @Test
    void staleStateReportFailsWithoutChangingProjection() {
        WorkerStateProjectionOwner owner = new WorkerStateProjectionOwner();
        WorkerStateReportEventHandler handler = new WorkerStateReportEventHandler(
                workerControlService(owner, TraceEventLogger.noop()));

        assertTrue(handler.handle(request(2, "READY"), new CoreEventPrincipal("worker-1", "worker"))
                .isSuccess());
        CoreEventResponse stale = handler.handle(request(1, "DRAINING"),
                new CoreEventPrincipal("worker-1", "worker"));

        assertFalse(stale.isSuccess());
        assertEquals(WorkerStateProjectionStatus.STALE.name(), stale.getCode());
        assertEquals("READY", owner.projection("worker-1").orElseThrow().state());
    }

    @Test
    void invalidStateReportIsRejectedBeforeProjectionMutation() {
        WorkerStateProjectionOwner owner = new WorkerStateProjectionOwner();
        WorkerStateReportEventHandler handler = new WorkerStateReportEventHandler(
                workerControlService(owner, TraceEventLogger.noop()));

        CoreEventResponse response = handler.handle(CoreEventRequest.builder()
                        .event(WorkerStateReportEventHandler.EVENT_CODE)
                        .requestId("invalid")
                        .payload(Map.of("workerId", "worker-1"))
                        .build(),
                new CoreEventPrincipal("worker-1", "worker"));

        assertFalse(response.isSuccess());
        assertEquals("INVALID_WORKER_STATE_REPORT", response.getCode());
        assertTrue(owner.projection("worker-1").isEmpty());
    }

    private static CoreEventRequest request(long version, String state) {
        return CoreEventRequest.builder()
                .event(WorkerStateReportEventHandler.EVENT_CODE)
                .requestId("state-report-" + version)
                .payload(Map.of(
                        "workerId", "worker-1",
                        "stateVersion", version,
                        "state", state,
                        "reason", "test",
                        "observedAtEpochMillis", 1_779_000_000_000L + version,
                        "attributes", Map.of("temperature", "normal")
                ))
                .build();
    }

    private static WorkerControlService workerControlService(WorkerStateProjectionOwner owner,
                                                             TraceEventLogger traceEventLogger) {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
        return new WorkerControlService(
                workerManager,
                workerManager,
                workerManager,
                new WorkerCommandLifecycleOwner(),
                owner,
                traceEventLogger);
    }
}
