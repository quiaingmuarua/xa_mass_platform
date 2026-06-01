package com.xa.mass.engine.stage;

import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TaskStageEvidenceServiceTest {

    @Test
    void appliesStageEvidenceThroughOwnerBackedEntryAndReadView() {
        RecordingEventSink sink = new RecordingEventSink();
        TaskStageEvidenceService service = new TaskStageEvidenceService(
                new TaskStageEvidenceOwner(),
                new TraceEventLogger(sink));

        TaskStageEvidenceResult result = service.applyEvidence(TaskStageEvidence.builder(
                        "task-1", "msg-1", "fetch", 1)
                .stageStatus("STARTED")
                .build());

        assertTrue(result.success());
        assertEquals("STARTED", service.projection("task-1", "msg-1", "fetch")
                .orElseThrow()
                .stageStatus());
        assertEquals(1, service.projectionsForMessage("task-1", "msg-1").size());
        assertFalse(Boolean.TRUE.equals(sink.eventsOfType(ExecutionEventType.TASK_STAGE_EVIDENCE_APPLIED)
                .get(0)
                .getAttrs()
                .get("stableFinalResult")));
    }
}
