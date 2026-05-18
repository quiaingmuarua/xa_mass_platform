package com.xa.mass.engine.stage;

import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TaskStageEvidenceEventHandlerTest {

    @Test
    void stageEvidenceEventUpdatesProjectionAndEmitsNonFinalTrace() {
        TaskStageEvidenceOwner owner = new TaskStageEvidenceOwner();
        RecordingEventSink sink = new RecordingEventSink();
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        TaskStageEvidenceEventHandler handler = new TaskStageEvidenceEventHandler(
                owner,
                new TraceEventLogger(sink)
        );
        handler.register(new KernelEventHandlerRegistry(runtime));

        CoreEventResponse response = runtime.dispatch(request(1, "fetch", "STARTED"),
                new CoreEventPrincipal("worker-1", "worker"));

        assertTrue(response.isSuccess());
        assertEquals("STARTED", owner.projection("task-1", "msg-1", "fetch")
                .orElseThrow()
                .stageStatus());
        assertTrue(sink.events().stream()
                .anyMatch(event -> event.getEventType() == ExecutionEventType.TASK_STAGE_EVIDENCE_APPLIED
                        && "task-1".equals(event.getIdentity().taskId())
                        && "msg-1".equals(event.getIdentity().messageId())
                        && "fetch".equals(event.getAttrs().get("stageName"))
                        && "STARTED".equals(event.getAttrs().get("stageStatus"))
                        && Boolean.FALSE.equals(event.getAttrs().get("stableFinalResult"))));
    }

    @Test
    void staleStageEvidenceFailsWithoutChangingProjection() {
        TaskStageEvidenceOwner owner = new TaskStageEvidenceOwner();
        TaskStageEvidenceEventHandler handler = new TaskStageEvidenceEventHandler(
                owner,
                TraceEventLogger.noop()
        );

        assertTrue(handler.handle(request(2, "fetch", "DONE"), new CoreEventPrincipal("worker-1", "worker"))
                .isSuccess());
        CoreEventResponse stale = handler.handle(request(1, "fetch", "STARTED"),
                new CoreEventPrincipal("worker-1", "worker"));

        assertFalse(stale.isSuccess());
        assertEquals(TaskStageEvidenceStatus.STALE.name(), stale.getCode());
        assertEquals("DONE", owner.projection("task-1", "msg-1", "fetch").orElseThrow().stageStatus());
    }

    @Test
    void invalidStageEvidenceIsRejectedBeforeProjectionMutation() {
        TaskStageEvidenceOwner owner = new TaskStageEvidenceOwner();
        TaskStageEvidenceEventHandler handler = new TaskStageEvidenceEventHandler(
                owner,
                TraceEventLogger.noop()
        );

        CoreEventResponse response = handler.handle(CoreEventRequest.builder()
                        .event(TaskStageEvidenceEventHandler.EVENT_CODE)
                        .requestId("invalid")
                        .payload(Map.of("taskId", "task-1", "messageId", "msg-1"))
                        .build(),
                new CoreEventPrincipal("worker-1", "worker"));

        assertFalse(response.isSuccess());
        assertEquals("INVALID_TASK_STAGE_EVIDENCE", response.getCode());
        assertTrue(owner.projection("task-1", "msg-1", "fetch").isEmpty());
    }

    private static CoreEventRequest request(long version, String stageName, String stageStatus) {
        return CoreEventRequest.builder()
                .event(TaskStageEvidenceEventHandler.EVENT_CODE)
                .requestId("stage-" + version)
                .payload(Map.of(
                        "taskId", "task-1",
                        "messageId", "msg-1",
                        "stageName", stageName,
                        "stageVersion", version,
                        "stageStatus", stageStatus,
                        "detail", "stage evidence",
                        "observedAtEpochMillis", 1_779_000_000_000L + version,
                        "attributes", Map.of("items", 10)
                ))
                .build();
    }
}
