package com.xa.mass.engine.command;

import com.xa.mass.engine.InMemoryWorkerDeclarationRuntimeStore;

import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.control.WorkerControlService;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.worker.runtime.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResultCode;
import com.xa.mass.worker.runtime.command.WorkerCommandStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCommandRequestEventHandlerTest {

    @Test
    void commandRequestEventRecordsLifecycleTruthAndTrace() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        RecordingEventSink sink = new RecordingEventSink();
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        WorkerCommandRequestEventHandler handler = new WorkerCommandRequestEventHandler(
                workerControlService(owner, new TraceEventLogger(sink)));
        handler.register(new KernelEventHandlerRegistry(runtime));

        CoreEventResponse response = runtime.dispatch(request("cmd-1", "worker-a", "PING"),
                new CoreEventPrincipal("operator-a", "operator"));

        assertTrue(response.isSuccess());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-1").orElseThrow().status());
        assertEquals("worker-a", owner.command("cmd-1").orElseThrow().workerId());
        assertTrue(sink.events().stream().anyMatch(event ->
                event.getEventType() == ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION
                        && "cmd-1".equals(event.getAttrs().get("commandId"))
                        && "REQUESTED".equals(event.getAttrs().get("commandStatus"))
                        && "ACCEPTED".equals(event.getAttrs().get("result"))));
    }

    @Test
    void duplicateDifferentCommandPayloadFailsWithoutChangingLifecycleTruth() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        WorkerCommandRequestEventHandler handler = new WorkerCommandRequestEventHandler(
                workerControlService(owner, TraceEventLogger.noop()));

        assertTrue(handler.handle(request("cmd-1", "worker-a", "PING"),
                new CoreEventPrincipal("operator-a", "operator")).isSuccess());

        CoreEventResponse conflict = handler.handle(request("cmd-1", "worker-a", "DRAIN"),
                new CoreEventPrincipal("operator-a", "operator"));

        assertFalse(conflict.isSuccess());
        assertEquals(WorkerCommandLifecycleResultCode.CONFLICT.name(), conflict.getCode());
        assertEquals("PING", owner.command("cmd-1").orElseThrow().commandType());
    }

    @Test
    void invalidCommandRequestIsRejectedBeforeOwnerMutation() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        WorkerCommandRequestEventHandler handler = new WorkerCommandRequestEventHandler(
                workerControlService(owner, TraceEventLogger.noop()));

        CoreEventResponse response = handler.handle(CoreEventRequest.builder()
                        .event(WorkerCommandRequestEventHandler.EVENT_CODE)
                        .requestId("invalid")
                        .payload(Map.of("commandId", "cmd-1"))
                        .build(),
                new CoreEventPrincipal("operator-a", "operator"));

        assertFalse(response.isSuccess());
        assertEquals("INVALID_WORKER_COMMAND_REQUEST", response.getCode());
        assertTrue(owner.command("cmd-1").isEmpty());
    }

    private static CoreEventRequest request(String commandId, String workerId, String commandType) {
        return CoreEventRequest.builder()
                .event(WorkerCommandRequestEventHandler.EVENT_CODE)
                .requestId("request-" + commandId)
                .payload(Map.of(
                        "commandId", commandId,
                        "workerId", workerId,
                        "commandType", commandType,
                        "requester", "operator-a",
                        "reason", "maintenance",
                        "idempotencyKey", "idem-" + commandId,
                        "deadlineEpochMillis", 1_779_000_000_000L,
                        "payload", Map.of("mode", "safe")
                ))
                .build();
    }

    private static WorkerControlService workerControlService(WorkerCommandLifecycleOwner owner,
                                                             TraceEventLogger traceEventLogger) {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        return new WorkerControlService(
                workerManager,
                workerManager,
                new DefaultWorkerDispatchAvailabilityPolicy(workerManager, workerManager),
                owner,
                new WorkerStateProjectionOwner(),
                traceEventLogger);
    }
}
