package com.xa.mass.trace.operator;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceOperatorServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private final TraceOperatorService operatorService = new TraceOperatorService();

    @Test
    void timelineReadsCanonicalSinkOutputWithoutCliAdapter() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        TraceTimelineResponse response = operatorService.timeline(
                new TraceTimelineRequest(tempDir.toString(), "task-1", null, null));

        assertEquals("task-1", response.taskId());
        assertNull(response.messageId());
        assertEquals(3, response.count());
        assertTrue(containsTimelineEvent(response, "TASK_STATUS_TRANSITION", "READY", "RUNNING"));
        assertTrue(containsTimelineEvent(response, "TASK_TERMINAL_CLOSED", "RUNNING", "TERMINAL"));
    }

    @Test
    void statsAggregatesCanonicalSinkOutputWithoutCliAdapter() throws Exception {
        writeDuplicateReplayTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        TraceStatsResponse response = operatorService.stats(
                new TraceStatsRequest(tempDir.toString(), "task-replay", null, null, null));

        assertEquals("task-replay", response.taskId());
        assertEquals(4, response.count());
        assertEquals("CALLBACK_ACCEPTED", response.rows().getFirst().eventType());
        assertEquals(2, response.rows().getFirst().count());
    }

    @Test
    void assignmentReadsScheduleFieldsFromCanonicalSinkOutput() throws Exception {
        writeAssignmentSuccessTrace(tempDir, "task-assignment");
        awaitJsonlFiles(tempDir, 1);

        TraceAssignmentResponse response = operatorService.assignment(
                new TraceAssignmentRequest(tempDir.toString(), "task-assignment", null));

        assertEquals("task-assignment", response.taskId());
        assertTrue(response.count() >= 6);
        var summary = response.events().stream()
                .filter(row -> "ASSIGNMENT_SUMMARY".equals(row.eventType()))
                .findFirst()
                .orElseThrow();
        assertEquals("SUCCESS", summary.result());
        assertEquals(2, summary.pendingDispatchCount());
        assertEquals(1, summary.usedWorkerCount());
        assertEquals("BULK", summary.workloadClass());
        assertEquals("NORMAL", summary.dispatchPriority());

        var binding = response.events().stream()
                .filter(row -> "DISPATCH_BINDING_SUMMARY".equals(row.eventType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, binding.dispatchedMessageCount());
        assertEquals(2, binding.perWorkerBatchLimit());
    }

    @Test
    void validateRejectsMalformedRowsWithoutCliAdapter() throws Exception {
        Path broken = tempDir.resolve("broken.jsonl");
        Files.writeString(broken, """
                {"schema":"xa.mass.execution-event.v1","eventType":"TASK_STATUS_TRANSITION","category":"TASK","severity":"INFO","ts":1,"tsIso":"2026-05-14T00:00:00Z","identity":{"taskId":"task-bad"}}
                {"schema":"xa.mass.execution-event.v1","eventType":"NOT_A_REAL_EVENT","category":"TASK","severity":"INFO","ts":2,"tsIso":"2026-05-14T00:00:01Z","identity":{"taskId":"task-bad"}}
                not-json
                """);

        TraceValidateResponse response = operatorService.validate(new TraceValidateRequest(broken.toString()));

        assertFalse(response.valid());
        assertEquals(1, response.source().fileCount());
        assertTrue(response.issues().size() >= 2);
    }

    @Test
    void analyzeRunsBuiltInScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeDuplicateReplayTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "duplicate-callback-replay", "task-replay"));

        assertTrue(response.ok());
        assertEquals("duplicate-callback-replay", response.scenarioId());
        assertEquals("task-replay", response.taskId());
        assertEquals(1L, response.eventTypeCounts().get("CALLBACK_IGNORED_DUPLICATE"));
    }

    @Test
    void analyzeRunsAssignmentSuccessBindingScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeAssignmentSuccessTrace(tempDir, "task-assignment-success");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "assignment-success-binding", "task-assignment-success"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("assignment-success-binding", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("DISPATCH_BINDING_SUMMARY"));
    }

    @Test
    void analyzeRunsAssignmentMinWorkerGateScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeAssignmentMinGateTrace(tempDir, "task-min-gate");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "assignment-min-worker-gate", "task-min-gate"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("assignment-min-worker-gate", response.scenarioId());
    }

    @Test
    void analyzeRunsAssignmentRetryRedispatchScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeAssignmentRetryTrace(tempDir, "task-retry");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "assignment-retry-redispatch", "task-retry"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("assignment-retry-redispatch", response.scenarioId());
    }

    @Test
    void analyzeRunsLoadAwareWorkerSelectionScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeLoadAwareWorkerSelectionTrace(tempDir, "task-load-aware");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "load-aware-worker-selection", "task-load-aware"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("load-aware-worker-selection", response.scenarioId());
        assertEquals(2L, response.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));
    }

    @Test
    void analyzeRunsCapacityReservationScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeCapacityReservationTrace(tempDir, "task-capacity");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "capacity-reservation-under-concurrency", "task-capacity"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("capacity-reservation-under-concurrency", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("WORKER_MATCH_REJECTED"));
    }

    @Test
    void assignmentSuccessBindingScenarioFailsWhenBindingIsMissing() throws Exception {
        writeAssignmentMinGateTrace(tempDir, "task-broken-assignment");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "assignment-success-binding", "task-broken-assignment"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_SUCCESS_DISPATCH_BINDING_SUMMARY".equals(issue.code())));
    }

    @Test
    void assignmentMinWorkerGateScenarioFailsWhenCountsDoNotShowGate() throws Exception {
        writeAssignmentMinGateBadCountsTrace(tempDir, "task-bad-min-gate-counts");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "assignment-min-worker-gate", "task-bad-min-gate-counts"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MIN_WORKER_GATE_COUNTS_NOT_OBSERVED".equals(issue.code())));
    }

    @Test
    void assignmentRetryRedispatchScenarioFailsWithoutRetryEvidence() throws Exception {
        writeAssignmentInitialSkipOnlyTrace(tempDir, "task-missing-retry-evidence");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "assignment-retry-redispatch", "task-missing-retry-evidence"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_RETRY_OR_REQUEUE_EVIDENCE".equals(issue.code())));
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_REDISPATCH_ASSIGNMENT_ATTEMPT".equals(issue.code())));
    }

    @Test
    void capacityReservationScenarioFailsWithoutCapacityRejection() throws Exception {
        writeLoadAwareWorkerSelectionTrace(tempDir, "task-missing-capacity-rejection");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "capacity-reservation-under-concurrency",
                        "task-missing-capacity-rejection"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_CAPACITY_REJECTION".equals(issue.code())));
    }

    @Test
    void scenarioRegistryStaysAvailableThroughOperatorService() {
        assertTrue(operatorService.scenarioIds().contains("single-message-success"));
        assertTrue(operatorService.scenarioIds().contains("duplicate-callback-replay"));
        assertTrue(operatorService.scenarioIds().contains("assignment-success-binding"));
        assertTrue(operatorService.scenarioIds().contains("assignment-min-worker-gate"));
        assertTrue(operatorService.scenarioIds().contains("assignment-retry-redispatch"));
        assertTrue(operatorService.scenarioIds().contains("load-aware-worker-selection"));
        assertTrue(operatorService.scenarioIds().contains("capacity-reservation-under-concurrency"));
    }

    private void writeCanonicalTrace(Path outputDir) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-1")
                    .identity(identity -> identity.taskId("task-1"))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "assignment-success", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-1")
                    .identity(identity -> identity.taskId("task-1").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "result-ingested", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .traceId("trace-1")
                    .identity(identity -> identity.taskId("task-1"))
                    .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                    .attrs(Map.of("reason", "all work converged", "source", "TaskManager", "terminalReason", "ALL_MESSAGES_SUCCEEDED"))
                    .build());
        }
    }

    private void writeDuplicateReplayTrace(Path outputDir) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay"))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "assignment-success", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay").messageId("msg-2").attemptId("attempt-2"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay"))
                    .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                    .attrs(Map.of("reason", "all work converged", "source", "TaskManager", "terminalReason", "ALL_MESSAGES_SUCCEEDED"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_IGNORED_DUPLICATE)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "duplicate callback suppressed")
                    .attrs(Map.of("reason", "duplicate callback suppressed", "source", "TaskManager"))
                    .build());
        }
    }

    private void writeAssignmentSuccessTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_REQUESTED)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched candidates produced dispatchable work",
                            "result", "SUCCESS",
                            "workloadClass", "BULK",
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-1").workerContextId("ctx-1"))
                    .outcome(true, null, "all rules matched and worker lock acquired")
                    .attrs(Map.of("source", "RuleBasedTaskWorkerMatchingStrategy", "reason", "all rules matched", "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_LOCK_ACQUIRED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-1"))
                    .attrs(Map.of("trigger", "TRY_LOCK_WORKER", "source", "RuleBasedTaskWorkerMatchingStrategy", "reason", "all rules matched", "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers dispatched",
                            "result", "SUCCESS",
                            "initialStatus", "READY",
                            "currentStatus", "RUNNING",
                            "pendingDispatchCount", 2,
                            "desiredDispatchWorkerCount", 1,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 1,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", 2,
                            "usedWorkerCount", 1,
                            "peakAssignedWorkerCount", 1,
                            "workloadClass", "BULK",
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_MSG_ASSIGN",
                            "source", "SimpleTaskDispatchBinder",
                            "reason", "runtime work bound to dispatch slots",
                            "result", "SUCCESS",
                            "pendingMessageCount", 2,
                            "matchedWorkerCount", 1,
                            "dispatchSlotCount", 1,
                            "dispatchedMessageCount", 2,
                            "unassignedMessageCount", 0,
                            "uniqueWorkerCount", 1,
                            "uniqueWorkerContextCount", 1,
                            "perWorkerBatchLimit", 2))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId))
                    .transition("READY", "RUNNING", "matched workers dispatched")
                    .attrs(Map.of("trigger", "ASSIGNMENT_SUCCEEDED", "source", "TaskWorkerAssignListener", "reason", "matched workers dispatched", "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-1").workerContextId("ctx-1"))
                    .transition("CREATED", "LEASED", "attempt leased for dispatch")
                    .attrs(Map.of("trigger", "BIND_TASK_MESSAGE", "source", "SimpleTaskDispatchBinder", "reason", "attempt leased for dispatch", "result", "SUCCESS", "attemptNo", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-1").workerContextId("ctx-1"))
                    .transition("LEASED", "DISPATCHED", "attempt dispatched")
                    .attrs(Map.of("trigger", "BIND_TASK_MESSAGE", "source", "SimpleTaskDispatchBinder", "reason", "attempt dispatched", "result", "SUCCESS", "attemptNo", 1))
                    .build());
        }
    }

    private void writeLoadAwareWorkerSelectionTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-low"))
                    .outcome(true, null, "all rules matched and worker lock acquired after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker lock acquired after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerActiveLeaseCount", 0,
                            "workerReservedCount", 0,
                            "workerDeclaredCapacity", 4,
                            "workerEstimatedLoadRatio", "0.0"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-high"))
                    .outcome(true, null, "all rules matched and worker lock acquired after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker lock acquired after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 2,
                            "candidateScore", "0.6",
                            "workerActiveLeaseCount", 3,
                            "workerReservedCount", 0,
                            "workerDeclaredCapacity", 4,
                            "workerEstimatedLoadRatio", "0.8"))
                    .build());
        }
    }

    private void writeCapacityReservationTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-1"))
                    .outcome(true, null, "all rules matched and worker lock acquired after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker lock acquired after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerActiveLeaseCount", 0,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 1,
                            "workerEstimatedLoadRatio", "1.0"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_REJECTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-1"))
                    .outcome(false, null, "worker capacity unavailable after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "worker capacity unavailable after candidate ranking",
                            "result", "REJECTED",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerActiveLeaseCount", 0,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 1,
                            "workerEstimatedLoadRatio", "1.0"))
                    .build());
        }
    }

    private void writeAssignmentMinGateTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_SKIPPED)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers below minimum start gate",
                            "result", "SKIPPED",
                            "requiredMinWorkerCount", 2))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers below minimum start gate",
                            "result", "SKIPPED",
                            "initialStatus", "READY",
                            "currentStatus", "READY",
                            "pendingDispatchCount", 2,
                            "desiredDispatchWorkerCount", 1,
                            "requiredStartWorkerCount", 2,
                            "requestedMatchCount", 2,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 0,
                            "dispatchedMessageCount", 0,
                            "usedWorkerCount", 0,
                            "peakAssignedWorkerCount", 0))
                    .build());
        }
    }

    private void writeAssignmentMinGateBadCountsTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_SKIPPED)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(Map.of(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers below minimum start gate",
                            "result", "SKIPPED",
                            "requiredMinWorkerCount", 2))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers below minimum start gate",
                            "result", "SKIPPED",
                            "initialStatus", "READY",
                            "currentStatus", "READY",
                            "pendingDispatchCount", 2,
                            "desiredDispatchWorkerCount", 2,
                            "requiredStartWorkerCount", 2,
                            "requestedMatchCount", 2,
                            "matchedWorkerCount", 2,
                            "dispatchCandidateCount", 0,
                            "dispatchedMessageCount", 0,
                            "usedWorkerCount", 0,
                            "peakAssignedWorkerCount", 0))
                    .build());
        }
    }

    private void writeAssignmentInitialSkipOnlyTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "no matched worker-context candidates",
                            "result", "SKIPPED",
                            "initialStatus", "READY",
                            "currentStatus", "READY",
                            "pendingDispatchCount", 1,
                            "desiredDispatchWorkerCount", 1,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 1,
                            "matchedWorkerCount", 0,
                            "dispatchCandidateCount", 0,
                            "dispatchedMessageCount", 0,
                            "usedWorkerCount", 0))
                    .build());
        }
    }

    private void writeAssignmentRetryTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "no matched worker-context candidates",
                            "result", "SKIPPED",
                            "initialStatus", "READY",
                            "currentStatus", "READY",
                            "pendingDispatchCount", 1,
                            "desiredDispatchWorkerCount", 1,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 1,
                            "matchedWorkerCount", 0,
                            "dispatchCandidateCount", 0,
                            "dispatchedMessageCount", 0,
                            "usedWorkerCount", 0))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_RETRY_SCHEDULED)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "NO_ASSIGNMENT_RESULT",
                            "source", "TaskAssignWorker",
                            "reason", "task remained eligible after assignment attempt",
                            "result", "SCHEDULED",
                            "currentStatus", "READY",
                            "retryDelayMillis", 100))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_QUEUE_SNAPSHOT)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "RETRY_ENQUEUED",
                            "source", "TaskAssignWorker",
                            "reason", "delayed retry enqueued task back into assignment signal queue",
                            "result", "SUCCESS",
                            "taskStatus", "READY",
                            "dispatchLane", "INTERACTIVE",
                            "queueDepth", 1,
                            "trackedBatchPendingCount", 1,
                            "scheduledRetryCount", 0,
                            "queueAction", "RETRY_ENQUEUED",
                            "retryDelayMillis", 100))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers dispatched",
                            "result", "SUCCESS",
                            "initialStatus", "READY",
                            "currentStatus", "RUNNING",
                            "pendingDispatchCount", 1,
                            "desiredDispatchWorkerCount", 1,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 1,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", 1,
                            "usedWorkerCount", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_MSG_ASSIGN",
                            "source", "SimpleTaskDispatchBinder",
                            "reason", "runtime work bound to dispatch slots",
                            "result", "SUCCESS",
                            "pendingMessageCount", 1,
                            "matchedWorkerCount", 1,
                            "dispatchSlotCount", 1,
                            "dispatchedMessageCount", 1,
                            "unassignedMessageCount", 0,
                            "uniqueWorkerCount", 1,
                            "uniqueWorkerContextCount", 1,
                            "perWorkerBatchLimit", 1))
                    .build());
        }
    }

    private void awaitJsonlFiles(Path outputDir, int minFiles) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            try (var files = Files.list(outputDir)) {
                long count = files
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .count();
                if (count >= minFiles) {
                    return;
                }
            }
            Thread.sleep(25L);
        }
        try (var files = Files.list(outputDir)) {
            long count = files
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .count();
            assertTrue(count >= minFiles, "Expected at least " + minFiles + " trace jsonl files but found " + count);
        }
    }

    private boolean containsTimelineEvent(TraceTimelineResponse response, String eventType, String src, String dst) {
        return response.events().stream().anyMatch(event ->
                eventType.equals(event.eventType())
                        && src.equals(event.src())
                        && dst.equals(event.dst()));
    }

    private static Map<String, Object> attrs(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("attrs requires key/value pairs");
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            attrs.put((String) values[i], values[i + 1]);
        }
        return attrs;
    }
}
