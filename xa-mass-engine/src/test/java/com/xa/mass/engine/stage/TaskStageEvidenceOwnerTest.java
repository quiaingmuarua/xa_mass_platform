package com.xa.mass.engine.stage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TaskStageEvidenceOwnerTest {

    @Test
    void appliesStageEvidenceIntoBoundedProjection() {
        TaskStageEvidenceOwner owner = new TaskStageEvidenceOwner(
                Clock.fixed(Instant.parse("2026-05-18T00:00:00Z"), ZoneOffset.UTC),
                2
        );

        TaskStageEvidenceResult first = owner.applyEvidence(evidence(1, "STARTED"));
        TaskStageEvidenceResult second = owner.applyEvidence(evidence(2, "RUNNING"));
        TaskStageEvidenceResult third = owner.applyEvidence(evidence(3, "DONE"));

        assertEquals(TaskStageEvidenceStatus.ACCEPTED, first.status());
        assertEquals(TaskStageEvidenceStatus.ACCEPTED, second.status());
        assertEquals(TaskStageEvidenceStatus.ACCEPTED, third.status());
        TaskStageProjection projection = owner.projection("task-1", "msg-1", "fetch").orElseThrow();
        assertEquals("DONE", projection.stageStatus());
        assertEquals(3, projection.stageVersion());
        assertEquals(2, projection.recentEvidence().size());
        assertEquals("DONE", projection.recentEvidence().get(0).stageStatus());
        assertEquals("RUNNING", projection.recentEvidence().get(1).stageStatus());
    }

    @Test
    void stageEvidenceIsScopedByTaskMessageAndStageName() {
        TaskStageEvidenceOwner owner = new TaskStageEvidenceOwner();

        owner.applyEvidence(evidence("task-1", "msg-1", "fetch", 1, "DONE"));
        owner.applyEvidence(evidence("task-1", "msg-1", "parse", 1, "STARTED"));
        owner.applyEvidence(evidence("task-1", "msg-2", "fetch", 1, "STARTED"));

        assertEquals(2, owner.projectionsForMessage("task-1", "msg-1").size());
        assertEquals("DONE", owner.projection("task-1", "msg-1", "fetch").orElseThrow().stageStatus());
        assertEquals("STARTED", owner.projection("task-1", "msg-1", "parse").orElseThrow().stageStatus());
        assertEquals("STARTED", owner.projection("task-1", "msg-2", "fetch").orElseThrow().stageStatus());
    }

    @Test
    void rejectsStaleAndConflictingEvidenceWithoutChangingProjection() {
        TaskStageEvidenceOwner owner = new TaskStageEvidenceOwner();
        TaskStageEvidence accepted = evidence(7, "DONE");

        assertEquals(TaskStageEvidenceStatus.ACCEPTED, owner.applyEvidence(accepted).status());
        assertEquals(TaskStageEvidenceStatus.IDEMPOTENT, owner.applyEvidence(accepted).status());

        TaskStageEvidenceResult stale = owner.applyEvidence(evidence(6, "RUNNING"));
        assertEquals(TaskStageEvidenceStatus.STALE, stale.status());
        assertTrue(!stale.projectionChanged());

        TaskStageEvidenceResult conflict = owner.applyEvidence(
                TaskStageEvidence.builder("task-1", "msg-1", "fetch", 7)
                        .stageStatus("FAILED")
                        .detail("different payload")
                        .observedAt(Instant.ofEpochMilli(7_000L))
                        .attributes(Map.of("items", 11))
                        .build()
        );
        assertEquals(TaskStageEvidenceStatus.CONFLICT, conflict.status());
        assertTrue(!conflict.projectionChanged());
        assertEquals("DONE", owner.projection("task-1", "msg-1", "fetch").orElseThrow().stageStatus());
    }

    private static TaskStageEvidence evidence(long version, String stageStatus) {
        return evidence("task-1", "msg-1", "fetch", version, stageStatus);
    }

    private static TaskStageEvidence evidence(String taskId,
                                              String messageId,
                                              String stageName,
                                              long version,
                                              String stageStatus) {
        return TaskStageEvidence.builder(taskId, messageId, stageName, version)
                .stageStatus(stageStatus)
                .detail("test")
                .observedAt(Instant.ofEpochMilli(version * 1_000L))
                .attributes(Map.of("items", 10))
                .build();
    }
}
