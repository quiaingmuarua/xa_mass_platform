package com.xa.mass.trace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaMassTraceCliIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void timelineReadsCanonicalJsonlProducedBySink() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("timeline", "--path", tempDir.toString(), "--task-id", "task-1", "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertEquals("task-1", root.get("taskId").asText());
        assertEquals(3, root.get("count").asInt());
        assertTrue(containsTimelineEvent(root, "TASK_STATUS_TRANSITION", "READY", "RUNNING"));
        assertTrue(containsTimelineEvent(root, "TASK_TERMINAL_CLOSED", "RUNNING", "TERMINAL"));
        assertTrue(result.stderr.isBlank());
    }

    @Test
    void statsAggregatesAcrossRotatedFilesProducedBySink() throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(tempDir.toString(), 128, 1)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-a")
                    .identity(identity -> identity.taskId("task-2"))
                    .transition("READY", "RUNNING", "leased")
                    .attrs(Map.of("reason", "leased", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-a")
                    .identity(identity -> identity.taskId("task-2").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-a")
                    .identity(identity -> identity.taskId("task-2").messageId("msg-2").attemptId("attempt-2"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
        }
        awaitJsonlFiles(tempDir, 2);

        CommandResult result = run("stats", "--path", tempDir.toString(), "--task-id", "task-2", "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertEquals(2, root.get("count").asInt());
        JsonNode first = root.get("rows").get(0);
        assertEquals("CALLBACK_ACCEPTED", first.get("eventType").asText());
        assertEquals(2, first.get("count").asInt());
    }

    @Test
    void assignmentJsonReadsScheduleFieldsProducedBySink() throws Exception {
        writeAssignmentSuccessTrace(tempDir, "task-assignment-cli");
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("assignment", "--path", tempDir.toString(), "--task-id", "task-assignment-cli", "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertEquals("task-assignment-cli", root.get("taskId").asText());
        assertTrue(root.get("count").asInt() >= 3);
        JsonNode summary = findEvent(root, "ASSIGNMENT_SUMMARY");
        assertEquals("SUCCESS", summary.get("result").asText());
        assertEquals("NORMAL", summary.get("dispatchPriority").asText());
        assertTrue(summary.get("foreground").asBoolean());
        assertEquals(1, summary.get("usedWorkerCount").asInt());
        assertEquals(4, summary.get("workerBudget").asInt());
        assertEquals(1, summary.get("currentTaskWorkerCount").asInt());
        assertTrue(summary.get("budgetLimited").asBoolean());
        JsonNode binding = findEvent(root, "DISPATCH_BINDING_SUMMARY");
        assertEquals(2, binding.get("dispatchedMessageCount").asInt());
        assertEquals(2, binding.get("perWorkerBatchLimit").asInt());
    }

    @Test
    void assignmentHumanOutputIncludesReasonResultAndCounts() throws Exception {
        writeAssignmentSuccessTrace(tempDir, "task-assignment-human");
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("assignment", "--path", tempDir.toString(), "--task-id", "task-assignment-human");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        assertTrue(result.stdout.contains("ASSIGNMENT_SUMMARY"));
        assertTrue(result.stdout.contains("result=SUCCESS"));
        assertTrue(result.stdout.contains("priority=NORMAL"));
        assertTrue(result.stdout.contains("foreground=true"));
        assertTrue(result.stdout.contains("reason=matched workers dispatched"));
        assertTrue(result.stdout.contains("dispatched=2"));
        assertTrue(result.stdout.contains("budget=4"));
        assertTrue(result.stdout.contains("taskWorkers=1"));
    }

    @Test
    void validateAcceptsCanonicalSinkOutputAndCountsRowsThroughDuckDb() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("validate", "--path", tempDir.toString(), "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("valid").asBoolean());
        assertEquals(3, root.get("validRows").asInt());
        assertEquals(0, root.get("issues").size());
    }

    @Test
    void validateRejectsMalformedOrNonCanonicalRows() throws Exception {
        Path broken = tempDir.resolve("broken.jsonl");
        Files.writeString(broken, """
                {"schema":"xa.mass.execution-event.v1","eventType":"TASK_STATUS_TRANSITION","category":"TASK","severity":"INFO","ts":1,"tsIso":"2026-05-14T00:00:00Z","identity":{"taskId":"task-bad"}}
                {"schema":"xa.mass.execution-event.v1","eventType":"NOT_A_REAL_EVENT","category":"TASK","severity":"INFO","ts":2,"tsIso":"2026-05-14T00:00:01Z","identity":{"taskId":"task-bad"}}
                {"schema":"xa.mass.execution-event.v1","category":"TASK","severity":"INFO","ts":3,"tsIso":"2026-05-14T00:00:02Z","identity":{"taskId":"task-bad"}}
                not-json
                """);

        CommandResult result = run("validate", "--path", broken.toString(), "--json");

        assertEquals(3, result.exitCode);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertFalse(root.get("valid").asBoolean());
        assertTrue(root.get("issues").size() >= 3);
        assertTrue(result.stderr.isBlank());
    }

    @Test
    void analyzeRecognizesSingleMessageSuccessScenarioFromCanonicalTrace() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("analyze",
                "--path", tempDir.toString(),
                "--scenario", "single-message-success",
                "--task-id", "task-1",
                "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("ok").asBoolean());
        assertEquals("single-message-success", root.get("scenarioId").asText());
        assertEquals("task-1", root.get("taskId").asText());
        assertTrue(result.stderr.isBlank());
    }

    @Test
    void analyzeRecognizesDuplicateCallbackReplayScenarioFromCanonicalTrace() throws Exception {
        writeDuplicateReplayTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("analyze",
                "--path", tempDir.toString(),
                "--scenario", "duplicate-callback-replay",
                "--task-id", "task-replay",
                "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("ok").asBoolean());
        assertEquals("duplicate-callback-replay", root.get("scenarioId").asText());
        assertEquals(1, root.get("eventTypeCounts").get("CALLBACK_IGNORED_DUPLICATE").asInt());
    }

    @Test
    void analyzeRecognizesAssignmentSuccessBindingScenarioFromCanonicalTrace() throws Exception {
        writeAssignmentSuccessTrace(tempDir, "task-assignment-analyze");
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("analyze",
                "--path", tempDir.toString(),
                "--scenario", "assignment-success-binding",
                "--task-id", "task-assignment-analyze",
                "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("ok").asBoolean());
        assertEquals("assignment-success-binding", root.get("scenarioId").asText());
    }

    @Test
    void analyzeFailsWhenScenarioExpectationsAreNotMet() throws Exception {
        Path broken = tempDir.resolve("broken-scenario.jsonl");
        Files.writeString(broken, """
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-1","eventType":"TASK_STATUS_TRANSITION","category":"TASK","severity":"INFO","ts":1,"tsIso":"2026-05-14T00:00:00Z","traceId":"trace-x","identity":{"taskId":"task-missing"},"transition":{"src":"READY","dst":"RUNNING","reason":"leased"},"outcome":{},"attrs":{"source":"TaskManager","reason":"leased"}}
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-2","eventType":"TASK_TERMINAL_CLOSED","category":"TASK","severity":"INFO","ts":2,"tsIso":"2026-05-14T00:00:01Z","traceId":"trace-x","identity":{"taskId":"task-missing"},"transition":{"src":"RUNNING","dst":"TERMINAL","reason":"all done"},"outcome":{},"attrs":{"source":"TaskManager","reason":"all done","terminalReason":"ALL_MESSAGES_SUCCEEDED"}}
                """);

        CommandResult result = run("analyze",
                "--path", broken.toString(),
                "--scenario", "single-message-success",
                "--task-id", "task-missing",
                "--json");

        assertEquals(4, result.exitCode);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertFalse(root.get("ok").asBoolean());
        assertTrue(root.get("issues").size() >= 1);
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
                    .eventType(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                    .identity(identity -> identity.taskId(taskId).workerId("worker-1").workerContextId("ctx-1"))
                    .outcome(true, null, "all rules matched")
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

    private CommandResult run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = com.xa.mass.trace.cli.XaMassTraceCli.run(
                args,
                new PrintStream(stdout, true),
                new PrintStream(stderr, true)
        );
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    private boolean containsTimelineEvent(JsonNode root, String eventType, String src, String dst) {
        for (JsonNode event : root.withArray("events")) {
            if (eventType.equals(event.path("eventType").asText())
                    && src.equals(event.path("src").asText())
                    && dst.equals(event.path("dst").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode findEvent(JsonNode root, String eventType) {
        for (JsonNode event : root.withArray("events")) {
            if (eventType.equals(event.path("eventType").asText())) {
                return event;
            }
        }
        throw new AssertionError("Missing eventType=" + eventType + " in " + root);
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

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
