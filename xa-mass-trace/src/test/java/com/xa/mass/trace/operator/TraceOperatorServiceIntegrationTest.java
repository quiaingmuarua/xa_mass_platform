package com.xa.mass.trace.operator;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void queryReadsIdentityFilteredEventsInStableOrder() throws Exception {
        writeIdentityQueryTrace(tempDir.resolve("identity-query.jsonl"));

        TraceQueryResponse workerResponse = operatorService.query(
                new TraceQueryRequest(tempDir.toString(), null, null, "worker-1", null, null, null, null));

        assertEquals("worker-1", workerResponse.workerId());
        assertEquals(2, workerResponse.count());
        assertEquals("evt-1", workerResponse.events().get(0).eventId());
        assertEquals("evt-2", workerResponse.events().get(1).eventId());

        TraceQueryResponse commandResponse = operatorService.query(
                new TraceQueryRequest(tempDir.toString(), null, null, null, "cmd-1", null, null, null));

        assertEquals("cmd-1", commandResponse.commandId());
        assertEquals(2, commandResponse.count());
        assertTrue(commandResponse.events().stream()
                .allMatch(row -> "cmd-1".equals(row.commandId())));

        TraceQueryResponse traceResponse = operatorService.query(
                new TraceQueryRequest(tempDir.toString(), null, null, null, null, "trace-operator", null, null));

        assertEquals("trace-operator", traceResponse.traceId());
        assertEquals(2, traceResponse.count());

        TraceQueryResponse eventTypeResponse = operatorService.query(
                new TraceQueryRequest(tempDir.toString(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "WORKER_COMMAND_STATUS_TRANSITION",
                        null));

        assertEquals(2, eventTypeResponse.count());
        assertTrue(eventTypeResponse.events().stream()
                .allMatch(row -> "WORKER_COMMAND_STATUS_TRANSITION".equals(row.eventType())));
    }

    @Test
    void queryRequiresAtLeastOneFilter() throws Exception {
        writeIdentityQueryTrace(tempDir.resolve("identity-query.jsonl"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> operatorService.query(new TraceQueryRequest(tempDir.toString(),
                        null, null, null, null, null, null, null)));

        assertTrue(error.getMessage().contains("At least one query filter is required"));
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
        assertEquals(true, summary.foreground());
        assertEquals(4, summary.workerBudget());
        assertEquals(1, summary.currentTaskWorkerCount());
        assertEquals(true, summary.budgetLimited());

        var binding = response.events().stream()
                .filter(row -> "DISPATCH_BINDING_SUMMARY".equals(row.eventType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, binding.dispatchedMessageCount());
        assertEquals(2, binding.perWorkerBatchLimit());
    }

    @Test
    void assignmentReadsLateAttrsAcrossRotatedCanonicalSinkFiles() throws Exception {
        writeRotatedAssignmentTraceWithLateBudgetFields(tempDir, "task-rotated-assignment");
        awaitJsonlFiles(tempDir, 121);

        TraceAssignmentResponse response = operatorService.assignment(
                new TraceAssignmentRequest(tempDir.toString(), "task-rotated-assignment", null));

        var summary = response.events().stream()
                .filter(row -> "ASSIGNMENT_SUMMARY".equals(row.eventType()))
                .findFirst()
                .orElseThrow();
        assertEquals("SUCCESS", summary.result());
        assertEquals(100, summary.pendingDispatchCount());
        assertEquals(20, summary.workerBudget());
        assertEquals(true, summary.budgetLimited());
        assertEquals(20, summary.dispatchCandidateCount());
        assertEquals(20, summary.usedWorkerCount());
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
    void analyzeRunsLateStaleResultReplayScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeLateStaleResultReplayTrace(tempDir, "task-late-stale-replay", true);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "late-stale-result-replay", "task-late-stale-replay"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("late-stale-result-replay", response.scenarioId());
        assertEquals("task-late-stale-replay", response.taskId());
        assertEquals(1L, response.eventTypeCounts().get("CALLBACK_IGNORED_LATE"));
    }

    @Test
    void analyzeRunsAllFailedTerminalConvergenceScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeAllFailedTerminalTrace(tempDir, "task-all-failed");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "all-failed-terminal-convergence", "task-all-failed"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("all-failed-terminal-convergence", response.scenarioId());
        assertEquals("task-all-failed", response.taskId());
        assertEquals(1L, response.eventTypeCounts().get("TASK_TERMINAL_CLOSED"));
    }

    @Test
    void singleMessageSuccessScenarioFailsWhenTerminalPrecedesCallback() throws Exception {
        writeTerminalBeforeCallbackTrace(tempDir, "task-bad-sequence");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "single-message-success", "task-bad-sequence"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "SINGLE_MESSAGE_SUCCESS_SEQUENCE_MISMATCH".equals(issue.code())));
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
    void analyzeRunsLeaseExpiryRedispatchScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeLeaseExpiryRedispatchTrace(tempDir, "task-lease-expiry-redispatch");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "lease-expiry-redispatch", "task-lease-expiry-redispatch"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("lease-expiry-redispatch", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("LEASE_EXPIRED"));
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
    void analyzeRunsBackgroundWorkerSharingScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeBackgroundWorkerSharingTrace(tempDir, "task-background-sharing");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "background-worker-sharing", "task-background-sharing"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("background-worker-sharing", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));
    }

    @Test
    void analyzeRunsWorkerAttributeRoutingWithoutContextScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeWorkerAttributeRoutingWithoutContextTrace(tempDir, "task-worker-attribute-routing");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "worker-attribute-routing-without-context",
                        "task-worker-attribute-routing"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("worker-attribute-routing-without-context", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));
    }

    @Test
    void analyzeRunsGroupCapabilityRoutingScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeGroupCapabilityRoutingTrace(tempDir, "task-group-capability-routing", true);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "group-capability-routing",
                        "task-group-capability-routing"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("group-capability-routing", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));
    }

    @Test
    void analyzeRunsWorkerResourceCleanupWithoutContextScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeWorkerResourceCleanupWithoutContextTrace(tempDir, "task-worker-resource-cleanup");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "worker-resource-cleanup-without-context",
                        "task-worker-resource-cleanup"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("worker-resource-cleanup-without-context", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("RESOURCE_RELEASED"));
    }

    @Test
    void analyzeRunsCrossTaskWorkerFairnessScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeCrossTaskWorkerFairnessTrace(tempDir,
                "task-bulk-pressure",
                "task-interactive-progress",
                true,
                false);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "cross-task-worker-fairness",
                        "task-bulk-pressure,task-interactive-progress"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("cross-task-worker-fairness", response.scenarioId());
        assertEquals(2L, response.eventTypeCounts().get("ASSIGNMENT_SUMMARY"));
    }

    @Test
    void analyzeRunsLateWorkerBackfillScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeLateWorkerBackfillTrace(tempDir, "task-late-backfill", "worker-late-01", true);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "late-worker-backfill",
                        "task-late-backfill,worker-late-01"));

        assertTrue(response.ok(), response.issues().toString());
        assertEquals("late-worker-backfill", response.scenarioId());
        assertEquals(1L, response.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));
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
    void leaseExpiryRedispatchScenarioFailsWhenTakeoverNeverBindsAfterExpiry() throws Exception {
        writeLeaseExpiryRedispatchTrace(tempDir, "task-bad-lease-expiry-redispatch", false);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "lease-expiry-redispatch",
                        "task-bad-lease-expiry-redispatch"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_BINDING_AROUND_EXPIRY".equals(issue.code())
                        || "MISSING_INITIAL_AND_REDISPATCH_BINDINGS".equals(issue.code())));
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
    void backgroundWorkerSharingScenarioFailsWhenWorkerLockIsObserved() throws Exception {
        writeBackgroundWorkerSharingTraceWithLock(tempDir, "task-background-lock");
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "background-worker-sharing", "task-background-lock"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "BACKGROUND_WORKER_LOCK_ACQUIRED".equals(issue.code())));
    }

    @Test
    void groupCapabilityRoutingScenarioFailsWithoutGroupIndexEvidence() throws Exception {
        writeGroupCapabilityRoutingTrace(tempDir, "task-missing-group-index-evidence", false);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "group-capability-routing",
                        "task-missing-group-index-evidence"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_GROUP_INDEX_ACCEPTED_MATCH".equals(issue.code())));
    }

    @Test
    void crossTaskWorkerFairnessScenarioFailsWhenBulkIsNotBudgetLimited() throws Exception {
        writeCrossTaskWorkerFairnessTrace(tempDir,
                "task-bulk-unbounded",
                "task-interactive-after-unbounded-bulk",
                false,
                false);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "cross-task-worker-fairness",
                        "task-bulk-unbounded,task-interactive-after-unbounded-bulk"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_BULK_BUDGET_LIMIT".equals(issue.code())));
    }

    @Test
    void crossTaskWorkerFairnessScenarioFailsWhenInteractiveReusesBulkWorker() throws Exception {
        writeCrossTaskWorkerFairnessTrace(tempDir,
                "task-bulk-overlap",
                "task-interactive-overlap",
                true,
                true);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "cross-task-worker-fairness",
                        "task-bulk-overlap,task-interactive-overlap"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "INTERACTIVE_REUSED_BULK_WORKER".equals(issue.code())));
    }

    @Test
    void lateWorkerBackfillScenarioFailsWithoutLateWorkerAcceptedMatch() throws Exception {
        writeLateWorkerBackfillTrace(tempDir, "task-missing-late-worker", "worker-other-01", true);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "late-worker-backfill",
                        "task-missing-late-worker,worker-late-01"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_LATE_WORKER_ACCEPTED_MATCH".equals(issue.code())));
    }

    @Test
    void lateWorkerBackfillScenarioFailsWithoutDispatchAfterLateWorkerMatch() throws Exception {
        writeLateWorkerBackfillTrace(tempDir, "task-missing-backfill-dispatch", "worker-late-01", false);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(),
                        "late-worker-backfill",
                        "task-missing-backfill-dispatch,worker-late-01"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_BACKFILL_DISPATCH_BINDING".equals(issue.code())));
    }

    @Test
    void lateStaleResultReplayScenarioFailsWhenReplayIsRejectedAsMissingLease() throws Exception {
        writeLateStaleResultReplayTrace(tempDir, "task-bad-late-stale-replay", false);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "late-stale-result-replay", "task-bad-late-stale-replay"));

        assertFalse(response.ok());
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "MISSING_LATE_OR_DUPLICATE_REPLAY_SUPPRESSION".equals(issue.code())));
        assertTrue(response.issues().stream()
                .anyMatch(issue -> "UNEXPECTED_CALLBACK_REJECTED_NO_ACTIVE_LEASE".equals(issue.code())));
    }

    @Test
    void scenarioRegistryStaysAvailableThroughOperatorService() {
        assertTrue(operatorService.scenarioIds().contains("single-message-success"));
        assertTrue(operatorService.scenarioIds().contains("all-failed-terminal-convergence"));
        assertTrue(operatorService.scenarioIds().contains("duplicate-callback-replay"));
        assertTrue(operatorService.scenarioIds().contains("late-stale-result-replay"));
        assertTrue(operatorService.scenarioIds().contains("assignment-success-binding"));
        assertTrue(operatorService.scenarioIds().contains("assignment-min-worker-gate"));
        assertTrue(operatorService.scenarioIds().contains("assignment-retry-redispatch"));
        assertTrue(operatorService.scenarioIds().contains("lease-expiry-redispatch"));
        assertTrue(operatorService.scenarioIds().contains("load-aware-worker-selection"));
        assertTrue(operatorService.scenarioIds().contains("capacity-reservation-under-concurrency"));
        assertTrue(operatorService.scenarioIds().contains("background-worker-sharing"));
        assertTrue(operatorService.scenarioIds().contains("worker-attribute-routing-without-context"));
        assertTrue(operatorService.scenarioIds().contains("group-capability-routing"));
        assertTrue(operatorService.scenarioIds().contains("cross-task-worker-fairness"));
        assertTrue(operatorService.scenarioIds().contains("worker-resource-cleanup-without-context"));
        assertTrue(operatorService.scenarioIds().contains("late-worker-backfill"));
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

    private void writeLateStaleResultReplayTrace(Path outputDir,
                                                 String taskId,
                                                 boolean suppressAsLate) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:00:00Z"))
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-late-stale")
                    .identity(identity -> identity.taskId(taskId))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "assignment-success", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:00:01Z"))
                    .eventType(ExecutionEventType.LEASE_EXPIRED)
                    .traceId("trace-late-stale")
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-chaos"))
                    .attrs(Map.of("reason", "lease-expired", "source", "LeaseExpireWatchdog"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:00:02Z"))
                    .eventType(ExecutionEventType.TASK_WORK_RETRY_RESET)
                    .traceId("trace-late-stale")
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1"))
                    .transition("EXPIRED", "INIT", "retry reset after expiry")
                    .attrs(Map.of("reason", "retry reset after expiry", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:00:03Z"))
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-late-stale")
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-2").workerId("worker-steady"))
                    .outcome(true, null, "accepted takeover result")
                    .attrs(Map.of("reason", "accepted takeover result", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:00:04Z"))
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .traceId("trace-late-stale")
                    .identity(identity -> identity.taskId(taskId))
                    .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                    .attrs(Map.of("reason", "all work converged", "source", "TaskManager", "terminalReason", "ALL_MESSAGES_SUCCEEDED"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:00:05Z"))
                    .eventType(suppressAsLate
                            ? ExecutionEventType.CALLBACK_IGNORED_LATE
                            : ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_LEASE)
                    .traceId("trace-late-stale")
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-chaos"))
                    .outcome(true, null, suppressAsLate
                            ? "late stale callback suppressed"
                            : "stale callback rejected as missing lease")
                    .attrs(Map.of(
                            "reason", suppressAsLate
                                    ? "late stale callback suppressed"
                                    : "stale callback rejected as missing lease",
                            "source", "TaskResultService"))
                    .build());
        }
    }

    private void writeLeaseExpiryRedispatchTrace(Path outputDir, String taskId) throws Exception {
        writeLeaseExpiryRedispatchTrace(outputDir, taskId, true);
    }

    private void writeLeaseExpiryRedispatchTrace(Path outputDir,
                                                 String taskId,
                                                 boolean includeTakeoverBinding) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:10:00Z"))
                    .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                    .traceId("trace-lease-expiry-redispatch")
                    .identity(identity -> identity.taskId(taskId).workerId("worker-chaos").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "initial assignment bound")
                    .attrs(Map.of("reason", "initial assignment bound", "source", "SimpleTaskDispatchBinder", "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:10:01Z"))
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .traceId("trace-lease-expiry-redispatch")
                    .identity(identity -> identity.taskId(taskId).workerId("worker-chaos"))
                    .outcome(true, null, "initial assignment succeeded")
                    .attrs(Map.of("reason", "initial assignment succeeded", "source", "TaskWorkerAssignListener", "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:10:02Z"))
                    .eventType(ExecutionEventType.LEASE_EXPIRED)
                    .traceId("trace-lease-expiry-redispatch")
                    .identity(identity -> identity.taskId(taskId).workerId("worker-chaos").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(false, null, "leased work expired")
                    .attrs(Map.of("reason", "leased work expired", "source", "LeaseExpireWatchdog", "result", "INIT"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:10:03Z"))
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_CLOSED)
                    .traceId("trace-lease-expiry-redispatch")
                    .identity(identity -> identity.taskId(taskId).workerId("worker-chaos").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "lease expiry closed the first attempt")
                    .attrs(Map.of("reason", "lease expiry closed the first attempt", "source", "TaskManager", "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .timestamp(Instant.parse("2026-05-20T00:10:04Z"))
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .traceId("trace-lease-expiry-redispatch")
                    .identity(identity -> identity.taskId(taskId).workerId("worker-steady"))
                    .outcome(true, null, "redispatch assignment succeeded")
                    .attrs(Map.of("reason", "redispatch assignment succeeded", "source", "TaskWorkerAssignListener", "result", "SUCCESS"))
                    .build());
            if (includeTakeoverBinding) {
                sink.emit(ExecutionEvent.builder()
                        .timestamp(Instant.parse("2026-05-20T00:10:05Z"))
                        .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                        .traceId("trace-lease-expiry-redispatch")
                        .identity(identity -> identity.taskId(taskId).workerId("worker-steady").messageId("msg-1").attemptId("attempt-2"))
                        .outcome(true, null, "redispatch takeover bound")
                        .attrs(Map.of("reason", "redispatch takeover bound", "source", "SimpleTaskDispatchBinder", "result", "SUCCESS"))
                        .build());
            }
        }
    }

    private void writeTerminalBeforeCallbackTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .timestamp(Instant.parse("2026-05-18T00:00:00Z"))
                    .traceId("trace-bad-sequence")
                    .identity(identity -> identity.taskId(taskId))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "assignment-success", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .timestamp(Instant.parse("2026-05-18T00:00:01Z"))
                    .traceId("trace-bad-sequence")
                    .identity(identity -> identity.taskId(taskId))
                    .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                    .attrs(Map.of(
                            "reason", "all work converged",
                            "source", "TaskManager",
                            "terminalReason", "ALL_MESSAGES_SUCCEEDED"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .timestamp(Instant.parse("2026-05-18T00:00:02Z"))
                    .traceId("trace-bad-sequence")
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
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
                            "foreground", true,
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-1"))
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
                            "workerBudget", 4,
                            "currentTaskWorkerCount", 1,
                            "budgetLimited", true,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", 2,
                            "usedWorkerCount", 1,
                            "peakAssignedWorkerCount", 1,
                            "workloadClass", "BULK",
                            "foreground", true,
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
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-1"))
                    .transition("CREATED", "LEASED", "attempt leased for dispatch")
                    .attrs(Map.of("trigger", "BIND_TASK_MESSAGE", "source", "SimpleTaskDispatchBinder", "reason", "attempt leased for dispatch", "result", "SUCCESS", "attemptNo", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-1"))
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

    private void writeBackgroundWorkerSharingTrace(Path outputDir, String taskId) throws Exception {
        writeBackgroundWorkerSharingTrace(outputDir, taskId, false);
    }

    private void writeBackgroundWorkerSharingTraceWithLock(Path outputDir, String taskId) throws Exception {
        writeBackgroundWorkerSharingTrace(outputDir, taskId, true);
    }

    private void writeBackgroundWorkerSharingTrace(Path outputDir, String taskId, boolean includeLock) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            if (includeLock) {
                sink.emit(ExecutionEvent.builder()
                        .eventType(ExecutionEventType.WORKER_LOCK_ACQUIRED)
                        .identity(identity -> identity.taskId(taskId).workerId("worker-shared"))
                        .attrs(attrs(
                                "trigger", "TRY_LOCK_WORKER",
                                "source", "RuleBasedTaskWorkerMatchingStrategy",
                                "reason", "unexpected lock for background worker",
                                "result", "SUCCESS"))
                        .build());
            }
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-shared"))
                    .outcome(true, null, "all rules matched and worker capacity reserved after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker capacity reserved after candidate ranking",
                            "result", "SUCCESS",
                            "foreground", false,
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerActiveLeaseCount", 1,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 2,
                            "workerEstimatedLoadRatio", "1.0"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_MSG_ASSIGN",
                            "source", "SimpleTaskDispatchBinder",
                            "reason", "runtime work bound to dispatch slots",
                            "result", "SUCCESS",
                            "foreground", false,
                            "pendingMessageCount", 1,
                            "matchedWorkerCount", 1,
                            "dispatchSlotCount", 1,
                            "dispatchedMessageCount", 1,
                            "unassignedMessageCount", 0,
                            "uniqueWorkerCount", 1,
                            "perWorkerBatchLimit", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_REQUESTED)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched candidates produced dispatchable work",
                            "result", "SUCCESS",
                            "foreground", false,
                            "workloadClass", "BULK",
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId))
                    .transition("READY", "RUNNING", "matched workers dispatched")
                    .attrs(attrs(
                            "trigger", "ASSIGNMENT_SUCCEEDED",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers dispatched",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers dispatched",
                            "result", "SUCCESS",
                            "foreground", false,
                            "initialStatus", "READY",
                            "currentStatus", "RUNNING",
                            "pendingDispatchCount", 1,
                            "desiredDispatchWorkerCount", 1,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 1,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", 1,
                            "usedWorkerCount", 1,
                            "peakAssignedWorkerCount", 1,
                            "workloadClass", "BULK",
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-shared"))
                    .transition("CREATED", "LEASED", "attempt leased for dispatch")
                    .attrs(Map.of("trigger", "BIND_TASK_MESSAGE", "source", "SimpleTaskDispatchBinder", "reason", "attempt leased for dispatch", "result", "SUCCESS", "attemptNo", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-1").attemptId("attempt-1").workerId("worker-shared"))
                    .transition("LEASED", "DISPATCHED", "attempt dispatched")
                    .attrs(Map.of("trigger", "BIND_TASK_MESSAGE", "source", "SimpleTaskDispatchBinder", "reason", "attempt dispatched", "result", "SUCCESS", "attemptNo", 1))
                    .build());
        }
    }

    private void writeWorkerAttributeRoutingWithoutContextTrace(Path outputDir,
                                                                String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-attribute-us"))
                    .outcome(true, null, "all rules matched and worker lock acquired after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker lock acquired after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerSchedulingResourceId", "worker-attribute-us",
                            "workerSchedulingRoutingTags", "shared,us",
                            "workerSchedulingAttributes", Map.of("routingTag", "us", "country", "us"),
                            "workerSchedulingMatchesRoutingCode", true))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_LOCK_ACQUIRED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-attribute-us"))
                    .attrs(attrs(
                            "trigger", "TRY_LOCK_WORKER",
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched after candidate ranking",
                            "result", "SUCCESS"))
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
                            "perWorkerBatchLimit", 1))
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
                            "usedWorkerCount", 1,
                            "peakAssignedWorkerCount", 1,
                            "workloadClass", "BULK",
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
        }
    }

    private void writeGroupCapabilityRoutingTrace(Path outputDir,
                                                  String taskId,
                                                  boolean includeGroupIndexEvidence) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            Map<String, Object> matchAttrs = attrs(
                    "source", "RuleBasedTaskWorkerMatchingStrategy",
                    "reason", "all rules matched and worker capacity reserved after candidate ranking",
                    "result", "SUCCESS",
                    "candidateRank", 1,
                    "candidateScore", "0.1",
                    "workerSchedulingResourceId", "worker-group-east-01",
                    "workerSchedulingRoutingTags", "shared,us",
                    "workerSchedulingAttributes", Map.of("routingTag", "us", "country", "us"),
                    "workerSchedulingMatchesRoutingCode", true);
            if (includeGroupIndexEvidence) {
                matchAttrs.put("workerGroupId", "pool-east");
                matchAttrs.put("eventBindingKey", "demoApp:demo.dispatch");
                matchAttrs.put("workerCandidateSource", "GROUP_INDEX");
            }
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-group-east-01"))
                    .outcome(true, null, "all rules matched and worker capacity reserved after candidate ranking")
                    .attrs(matchAttrs)
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
                            "perWorkerBatchLimit", 1))
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
                            "usedWorkerCount", 1,
                            "peakAssignedWorkerCount", 1,
                            "workloadClass", "BULK",
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());
        }
    }

    private void writeLateWorkerBackfillTrace(Path outputDir,
                                              String taskId,
                                              String lateWorkerId,
                                              boolean includeDispatchBinding) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers below desired worker count",
                            "result", "SKIPPED",
                            "initialStatus", "READY",
                            "currentStatus", "READY",
                            "pendingDispatchCount", 4,
                            "desiredDispatchWorkerCount", 2,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 2,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", 1,
                            "usedWorkerCount", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId(lateWorkerId))
                    .outcome(true, null, "late worker matched and capacity reserved")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "late worker matched and capacity reserved",
                            "result", "SUCCESS",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerGroupId", "pool-late",
                            "eventBindingKey", "soakProject:soak.dispatch.0",
                            "workerCandidateSource", "GROUP_INDEX",
                            "workerSchedulingResourceId", lateWorkerId,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(taskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "late worker backfilled pending work",
                            "result", "SUCCESS",
                            "initialStatus", "READY",
                            "currentStatus", "RUNNING",
                            "pendingDispatchCount", 3,
                            "desiredDispatchWorkerCount", 2,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 1,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", includeDispatchBinding ? 1 : 0,
                            "usedWorkerCount", 1))
                    .build());
            if (includeDispatchBinding) {
                sink.emit(ExecutionEvent.builder()
                        .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                        .identity(identity -> identity.taskId(taskId))
                        .attrs(attrs(
                                "trigger", "ON_MSG_ASSIGN",
                                "source", "SimpleTaskDispatchBinder",
                                "reason", "late worker dispatch slot bound",
                                "result", "SUCCESS",
                                "pendingMessageCount", 3,
                                "matchedWorkerCount", 1,
                                "dispatchSlotCount", 1,
                                "dispatchedMessageCount", 1,
                                "unassignedMessageCount", 2,
                                "uniqueWorkerCount", 1,
                                "perWorkerBatchLimit", 1))
                        .build());
            }
        }
    }

    private void writeWorkerResourceCleanupWithoutContextTrace(Path outputDir,
                                                               String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-cleanup"))
                    .outcome(true, null, "all rules matched and worker lock acquired after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker lock acquired after candidate ranking",
                            "result", "SUCCESS",
                            "workloadClass", "INTERACTIVE",
                            "dispatchLane", "INTERACTIVE",
                            "dispatchPriority", "HIGH",
                            "foreground", true))
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
                            "perWorkerBatchLimit", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_ATTEMPT_CLOSED)
                    .identity(identity -> identity.taskId(taskId)
                            .messageId("msg-cleanup")
                            .attemptId("attempt-cleanup")
                            .workerId("worker-cleanup"))
                    .outcome(true, null, "work attempt succeeded")
                    .attrs(attrs(
                            "trigger", "HANDLE_TASK_RESULT",
                            "source", "TaskManager",
                            "reason", "work attempt succeeded",
                            "result", "SUCCESS",
                            "attemptStatus", "SUCCEEDED",
                            "attemptFinalReason", "SUCCESS",
                            "attemptNo", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_LOCK_RELEASED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-cleanup"))
                    .attrs(attrs(
                            "trigger", "ON_TASK_MESSAGE_ATTEMPT_CLOSED",
                            "source", "TaskResourceReleaseListener",
                            "reason", "worker has no in-flight messages",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.RESOURCE_RELEASED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-cleanup"))
                    .outcome(true, null, "worker resource released")
                    .attrs(attrs(
                            "trigger", "ON_TASK_MESSAGE_ATTEMPT_CLOSED",
                            "source", "TaskResourceReleaseListener",
                            "reason", "worker resource released",
                            "resourceKind", "WORKER_LOCK",
                            "result", "SUCCESS"))
                    .build());
        }
    }

    private void writeCrossTaskWorkerFairnessTrace(Path outputDir,
                                                   String bulkTaskId,
                                                   String interactiveTaskId,
                                                   boolean bulkBudgetLimited,
                                                   boolean interactiveReusesBulkWorker) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(bulkTaskId).workerId("worker-bulk-01"))
                    .outcome(true, null, "all rules matched and worker capacity reserved after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker capacity reserved after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerActiveLeaseCount", 0,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 1,
                            "workerEstimatedLoadRatio", "1.0"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(bulkTaskId).workerId("worker-bulk-02"))
                    .outcome(true, null, "all rules matched and worker capacity reserved after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker capacity reserved after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 2,
                            "candidateScore", "0.2",
                            "workerActiveLeaseCount", 0,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 1,
                            "workerEstimatedLoadRatio", "1.0"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                    .identity(identity -> identity.taskId(bulkTaskId))
                    .attrs(attrs(
                            "trigger", "ON_MSG_ASSIGN",
                            "source", "SimpleTaskDispatchBinder",
                            "reason", "runtime work bound to dispatch slots",
                            "result", "SUCCESS",
                            "pendingMessageCount", 100,
                            "matchedWorkerCount", 2,
                            "dispatchSlotCount", 2,
                            "dispatchedMessageCount", 20,
                            "unassignedMessageCount", 80,
                            "uniqueWorkerCount", 2,
                            "perWorkerBatchLimit", 10))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(bulkTaskId))
                    .attrs(attrs(
                            "trigger", "ON_TASK_ASSIGN",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers dispatched",
                            "result", "SUCCESS",
                            "initialStatus", "READY",
                            "currentStatus", "RUNNING",
                            "pendingDispatchCount", 100,
                            "desiredDispatchWorkerCount", 20,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 20,
                            "workerBudget", 20,
                            "currentTaskWorkerCount", 0,
                            "budgetLimited", bulkBudgetLimited,
                            "matchedWorkerCount", 20,
                            "dispatchCandidateCount", 20,
                            "dispatchedMessageCount", 20,
                            "usedWorkerCount", 20,
                            "peakAssignedWorkerCount", 20,
                            "workloadClass", "BULK",
                            "foreground", true,
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
                    .build());

            String interactiveWorkerId = interactiveReusesBulkWorker ? "worker-bulk-01" : "worker-interactive-01";
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(interactiveTaskId).workerId(interactiveWorkerId))
                    .outcome(true, null, "all rules matched and worker capacity reserved after candidate ranking")
                    .attrs(attrs(
                            "source", "RuleBasedTaskWorkerMatchingStrategy",
                            "reason", "all rules matched and worker capacity reserved after candidate ranking",
                            "result", "SUCCESS",
                            "candidateRank", 1,
                            "candidateScore", "0.1",
                            "workerActiveLeaseCount", 0,
                            "workerReservedCount", 1,
                            "workerDeclaredCapacity", 1,
                            "workerEstimatedLoadRatio", "1.0"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                    .identity(identity -> identity.taskId(interactiveTaskId))
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
                            "perWorkerBatchLimit", 1))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .identity(identity -> identity.taskId(interactiveTaskId))
                    .transition("READY", "RUNNING", "matched workers dispatched")
                    .attrs(attrs(
                            "trigger", "ASSIGNMENT_SUCCEEDED",
                            "source", "TaskWorkerAssignListener",
                            "reason", "matched workers dispatched",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.ASSIGNMENT_SUMMARY)
                    .identity(identity -> identity.taskId(interactiveTaskId))
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
                            "workerBudget", 5,
                            "currentTaskWorkerCount", 0,
                            "budgetLimited", false,
                            "matchedWorkerCount", 1,
                            "dispatchCandidateCount", 1,
                            "dispatchedMessageCount", 1,
                            "usedWorkerCount", 1,
                            "peakAssignedWorkerCount", 1,
                            "workloadClass", "INTERACTIVE",
                            "foreground", true,
                            "dispatchLane", "INTERACTIVE",
                            "dispatchPriority", "HIGH",
                            "batchPolicy", "SMALL",
                            "leaseProfile", "SHORT"))
                    .build());
        }
    }

    private void writeRotatedAssignmentTraceWithLateBudgetFields(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 256, 1)) {
            for (int i = 0; i < 120; i++) {
                int index = i;
                sink.emit(ExecutionEvent.builder()
                        .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                        .identity(identity -> identity.taskId(taskId).workerId("worker-" + index))
                        .outcome(true, null, "all rules matched")
                        .attrs(attrs(
                                "source", "RuleBasedTaskWorkerMatchingStrategy",
                                "reason", "all rules matched",
                                "result", "SUCCESS",
                                "workloadClass", "BULK",
                                "foreground", true,
                                "dispatchLane", "BULK",
                                "dispatchPriority", "NORMAL"))
                        .build());
            }
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
                            "pendingDispatchCount", 100,
                            "desiredDispatchWorkerCount", 20,
                            "requiredStartWorkerCount", 1,
                            "requestedMatchCount", 20,
                            "workerBudget", 20,
                            "currentTaskWorkerCount", 0,
                            "budgetLimited", true,
                            "matchedWorkerCount", 20,
                            "dispatchCandidateCount", 20,
                            "dispatchedMessageCount", 20,
                            "usedWorkerCount", 20,
                            "peakAssignedWorkerCount", 20,
                            "workloadClass", "BULK",
                            "foreground", true,
                            "dispatchLane", "BULK",
                            "dispatchPriority", "NORMAL",
                            "batchPolicy", "LARGE",
                            "leaseProfile", "NORMAL"))
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

    private void writeAllFailedTerminalTrace(Path outputDir, String taskId) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-a"))
                    .attrs(attrs(
                            "trigger", "HANDLE_TASK_RESULT",
                            "source", "TaskManager",
                            "reason", "callback accepted",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_LOGICALLY_FINAL)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-a"))
                    .attrs(attrs(
                            "trigger", "HANDLE_TASK_RESULT",
                            "source", "TaskManager",
                            "reason", "work item reached stable failure",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-b"))
                    .attrs(attrs(
                            "trigger", "HANDLE_TASK_RESULT",
                            "source", "TaskManager",
                            "reason", "callback accepted",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_WORK_LOGICALLY_FINAL)
                    .identity(identity -> identity.taskId(taskId).messageId("msg-b"))
                    .attrs(attrs(
                            "trigger", "HANDLE_TASK_RESULT",
                            "source", "TaskManager",
                            "reason", "work item reached stable failure",
                            "result", "SUCCESS"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .identity(identity -> identity.taskId(taskId))
                    .transition("RUNNING", "TERMINAL", "all work items finalized")
                    .attrs(attrs(
                            "trigger", "RESOLVE_TASK_STATE",
                            "source", "TaskManager",
                            "reason", "all work items finalized",
                            "terminalReason", "ALL_MESSAGES_FAILED"))
                    .build());
        }
    }

    private boolean containsTimelineEvent(TraceTimelineResponse response, String eventType, String src, String dst) {
        return response.events().stream().anyMatch(event ->
                eventType.equals(event.eventType())
                        && src.equals(event.src())
                        && dst.equals(event.dst()));
    }

    private void writeIdentityQueryTrace(Path path) throws Exception {
        Files.writeString(path, """
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-2","eventType":"WORKER_STATE_REPORT_APPLIED","category":"WORKER","severity":"INFO","ts":100,"tsIso":"2026-05-18T00:00:00Z","traceId":"trace-operator","spanId":"span-2","parentSpanId":"span-1","identity":{"taskId":"task-query","workerId":"worker-1"},"transition":{},"outcome":{},"attrs":{"source":"WorkerStateReportEventHandler","reason":"state report accepted","commandId":"cmd-1"}}
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-1","eventType":"WORKER_COMMAND_STATUS_TRANSITION","category":"WORKER","severity":"INFO","ts":100,"tsIso":"2026-05-18T00:00:00Z","traceId":"trace-operator","spanId":"span-1","identity":{"taskId":"task-query","workerId":"worker-1"},"transition":{"src":"REQUESTED","dst":"DELIVERY_ACCEPTED","reason":"accepted"},"outcome":{"success":true},"attrs":{"source":"WorkerCommandDeliveryCoordinator","reason":"delivery accepted","commandId":"cmd-1"}}
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-3","eventType":"WORKER_COMMAND_STATUS_TRANSITION","category":"WORKER","severity":"INFO","ts":200,"tsIso":"2026-05-18T00:00:01Z","traceId":"trace-other","spanId":"span-3","identity":{"taskId":"task-query","workerId":"worker-2"},"transition":{"src":"REQUESTED","dst":"DELIVERY_REJECTED","reason":"busy"},"outcome":{"success":false,"errorCode":"BUSY"},"attrs":{"source":"WorkerCommandDeliveryCoordinator","reason":"busy","commandId":"cmd-2"}}
                """);
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
